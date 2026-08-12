#!/usr/bin/env python3
"""인덱스 성능 측정 도구.

    python3 docs/perf/benchmark.py before     # 인덱스 없는 상태에서 측정
    mysql -u root -p growingpots_perf < docs/perf/indexes_add.sql
    python3 docs/perf/benchmark.py after      # 인덱스 적용 후 측정 + before와 자동 비교

측정 방식
- 각 쿼리를 EXPLAIN ANALYZE로 반복 실행한다. 클라이언트 왕복이 포함되는 셸 시간 대신
  MySQL이 보고하는 실제 실행 시간을 쓴다.
- 읽은 행 수(rows examined)를 함께 본다. 응답 시간은 머신 상태에 흔들리지만
  읽은 행 수는 흔들리지 않아 개선을 증명하기에 더 단단한 지표다.
- 쓰기 비용도 같이 잰다. 인덱스는 읽기를 빠르게 하는 대신 쓰기를 느리게 하므로,
  한쪽만 제시하면 정직한 측정이 아니다.
"""

import json
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

DB = os.environ.get("DB_NAME", "growingpots_perf")
USER = os.environ.get("DB_USERNAME", "root")
RUNS = int(os.environ.get("RUNS", "15"))
WARMUP = 3

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"

C = {
    "reset": "\033[0m", "dim": "\033[2m", "bold": "\033[1m",
    "cyan": "\033[36m", "green": "\033[32m", "yellow": "\033[33m",
    "red": "\033[31m", "blue": "\033[34m", "mag": "\033[35m",
}
if not sys.stdout.isatty() or os.environ.get("NO_COLOR"):
    C = {k: "" for k in C}


# ── 측정 대상 쿼리 ────────────────────────────────────────────────
# (id, 제목, 대응 리포지토리 메서드, SQL)
# {profile} {dept} {division}은 실행 시점에 DB에서 실재하는 ID로 치환된다.
# 하드코딩하면 시드를 다시 만들 때마다 AUTO_INCREMENT가 밀려 0행을 측정하게 된다.
QUERIES = [
    ("Q1", "과목 키워드 검색 — 조회 (LIKE)", "CourseSpecifications.withKeyword",
     "SELECT c.id FROM course c "
     "WHERE c.school_id = 1 AND c.is_active = 1 "
     "AND (c.name LIKE '%디자인%' OR c.course_code LIKE '%디자인%') LIMIT 20"),

    # Page<Course>는 조회와 별개로 count 쿼리를 반드시 한 번 더 날린다(CourseSpecifications.
    # withFetchedAssociations가 query.getResultType()==Long일 때 fetch join을 건너뛰는 게 그 증거).
    # count는 LIMIT이 없어 조건에 맞는 행을 전부 훑으므로, LIKE 비용의 진짜 크기는 이 쿼리에 있다.
    # Q1만 보고 "많이 개선됐다"고 하면 정직한 측정이 아니다.
    ("Q1C", "과목 키워드 검색 — count (LIKE)", "CourseRepository.findAll(spec, pageable) 내부 count",
     "SELECT COUNT(c.id) FROM course c "
     "WHERE c.school_id = 1 AND c.is_active = 1 "
     "AND (c.name LIKE '%디자인%' OR c.course_code LIKE '%디자인%')"),

    ("Q1F", "과목 키워드 검색 — 조회 (Full-Text)", "ngram Full-Text 인덱스 적용 시에만 측정",
     "SELECT c.id FROM course c "
     "WHERE c.school_id = 1 AND c.is_active = 1 "
     "AND MATCH(c.name) AGAINST('디자인' IN BOOLEAN MODE) LIMIT 20"),

    ("Q1FC", "과목 키워드 검색 — count (Full-Text)", "ngram Full-Text 인덱스 적용 시에만 측정",
     "SELECT COUNT(c.id) FROM course c "
     "WHERE c.school_id = 1 AND c.is_active = 1 "
     "AND MATCH(c.name) AGAINST('디자인' IN BOOLEAN MODE)"),

    ("Q2", "이수내역 status 필터", "findCourseIdsByStudentProfileAndStatusIn",
     "SELECT sc.course_id FROM student_course sc "
     "WHERE sc.student_profile_id = {profile} "
     "AND sc.status IN ('COMPLETED','IN_PROGRESS') AND sc.course_id IS NOT NULL"),

    ("Q3", "이수구분별 이수과목", "findByStudentProfileAndAppliedDivisionIn",
     "SELECT sc.id FROM student_course sc "
     "WHERE sc.student_profile_id = {profile} AND sc.applied_division_id IN ({division})"),

    ("Q4", "학과+이수구분 활성 과목", "findActiveByDepartmentAndDivisionCategory",
     "SELECT c.id FROM course c JOIN division d ON c.default_division_id = d.id "
     "WHERE c.offering_department_id = {dept} AND d.category = 'MAJOR_ELECTIVE' AND c.is_active = 1"),

    ("Q5", "영어강의 조회", "findByStudentProfileAndCourseIsEnglish",
     "SELECT sc.id FROM student_course sc JOIN course c ON sc.course_id = c.id "
     "WHERE sc.student_profile_id = {profile} AND c.is_english = 1 "
     "AND sc.status IN ('COMPLETED','IN_PROGRESS')"),
]

# {profile}은 실행 시점에 치환된다. 존재하지 않는 ID를 쓰면 FK 위반으로 조용히 실패한다.
WRITE_QUERY = (
    "INSERT INTO student_course "
    "(created_at, updated_at, student_profile_id, course_id, applied_division_id, "
    " raw_course_code, raw_course_name, credit, taken_year, taken_semester, is_retake, status, source, display_order) "
    "SELECT NOW(6), NOW(6), {profile}, c.id, c.default_division_id, 'BENCHWRITE', c.name, c.credit, "
    "2099, 'FIRST', 0, 'COMPLETED', 'PDF', 0 FROM course c LIMIT 500"
)
# 측정용으로 넣은 행만 정확히 지운다(taken_year=2099 + 전용 마커).
WRITE_CLEANUP = (
    "DELETE FROM student_course WHERE taken_year = 2099 AND raw_course_code = 'BENCHWRITE'"
)


def password():
    if os.environ.get("DB_PASSWORD"):
        return os.environ["DB_PASSWORD"]
    secret = HERE.parent.parent / "src/main/resources/application-secret.yml"
    if secret.exists():
        m = re.search(r'^DB_PASSWORD:\s*"?([^"\n]*)"?\s*$', secret.read_text(encoding="utf-8"), re.M)
        if m:
            return m.group(1)
    import getpass
    return getpass.getpass(f"MySQL password for {USER}: ")


PW = password()


def sql(query, database=DB):
    cmd = ["mysql", "-u", USER, f"-p{PW}", "--batch", "--raw", "-e", query]
    if database:
        cmd.insert(-2, database)
    r = subprocess.run(cmd, capture_output=True, text=True)
    err = "\n".join(l for l in r.stderr.splitlines() if "Using a password" not in l)
    return r.stdout, err


def explain_plan(q):
    out, err = sql("EXPLAIN " + q)
    if err:
        return None, err
    lines = out.strip().split("\n")
    if len(lines) < 2:
        return None, "no plan"
    hdr = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        d = dict(zip(hdr, line.split("\t")))
        rows.append({
            "table": d.get("table", "?"),
            "type": d.get("type", "?"),
            "key": d.get("key", "NULL"),
            "rows": d.get("rows", "?"),
            "filtered": d.get("filtered", "?"),
            "extra": d.get("Extra", ""),
        })
    return rows, None


ANALYZE_RE = re.compile(r"actual time=([\d.]+)\.\.([\d.]+) rows=([\d.]+) loops=(\d+)")


def measure(q):
    """EXPLAIN ANALYZE를 반복 실행해 실행 시간과 반환 행 수를 모은다."""
    times, rows_out = [], None
    for i in range(WARMUP + RUNS):
        out, err = sql("EXPLAIN ANALYZE " + q)
        if err:
            return None, err
        m = ANALYZE_RE.search(out)
        if not m:
            return None, "actual time 파싱 실패"
        if i >= WARMUP:
            times.append(float(m.group(2)))
            rows_out = float(m.group(3))
    return {"times": times, "rows_returned": rows_out}, None


def handler_rows(q):
    """실제로 읽은 행 수. Handler_read_* 상태값 차이로 구한다."""
    script = (
        "FLUSH STATUS; " + q + "; "
        "SHOW SESSION STATUS WHERE Variable_name IN "
        "('Handler_read_next','Handler_read_rnd_next','Handler_read_key','Handler_read_first');"
    )
    out, err = sql(script)
    if err:
        return None
    total = 0
    for line in out.strip().split("\n"):
        parts = line.split("\t")
        if len(parts) == 2 and parts[0].startswith("Handler_read_") and parts[1].isdigit():
            if parts[0] in ("Handler_read_next", "Handler_read_rnd_next"):
                total += int(parts[1])
    return total


def measure_write(params):
    q = WRITE_QUERY.format(**params)
    sql(WRITE_CLEANUP)
    durations = []
    for _ in range(5):
        t0 = time.perf_counter()
        _, err = sql(q)
        elapsed = (time.perf_counter() - t0) * 1000
        if err:
            return None, err.split("\n")[0][:70]
        durations.append(elapsed)
        sql(WRITE_CLEANUP)
    return durations, None


def resolve_params():
    """측정에 쓸 ID를 DB에서 실제로 찾아온다.

    시드를 다시 만들면 AUTO_INCREMENT가 이어져 ID 범위가 바뀐다. 값을 코드에 박아두면
    존재하지 않는 ID를 조회해 0행을 재게 되고, 그러면 아무것도 측정하지 못한 채
    '빠르다'는 착각만 남는다.
    """
    out, err = sql(
        "SELECT (SELECT student_profile_id FROM student_course GROUP BY student_profile_id "
        "        ORDER BY COUNT(*) DESC LIMIT 1),"
        " (SELECT offering_department_id FROM course WHERE is_active=1 AND offering_department_id IS NOT NULL "
        "  GROUP BY offering_department_id ORDER BY COUNT(*) DESC LIMIT 1),"
        " (SELECT applied_division_id FROM student_course WHERE applied_division_id IS NOT NULL "
        "  GROUP BY applied_division_id ORDER BY COUNT(*) DESC LIMIT 1)"
    )
    if err:
        raise SystemExit(f"파라미터 조회 실패: {err}")
    profile, dept, division = out.strip().split("\n")[-1].split("\t")
    return {"profile": profile, "dept": dept, "division": division}


def dwidth(s):
    """한글·한자는 터미널에서 두 칸을 차지한다. 박스 정렬에 필요하다."""
    import unicodedata
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in strip_ansi(s))


def pad(s, width):
    return s + " " * max(0, width - dwidth(s))


def pct(vals, p):
    s = sorted(vals)
    return s[min(int(len(s) * p / 100), len(s) - 1)]


def bar(value, vmax, width=28):
    if vmax <= 0:
        return ""
    n = max(1, int(round(width * value / vmax)))
    return "█" * n + C["dim"] + "·" * (width - n) + C["reset"]


def fmt_int(n):
    try:
        return f"{int(float(n)):,}"
    except (TypeError, ValueError):
        return str(n)


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "current"
    RESULTS.mkdir(exist_ok=True)

    meta, _ = sql("SELECT VERSION()", DB)
    version = meta.strip().split("\n")[-1] if meta else "?"
    counts, _ = sql("SELECT (SELECT COUNT(*) FROM course), (SELECT COUNT(*) FROM student_course)")
    c_cnt, sc_cnt = counts.strip().split("\n")[-1].split("\t")
    idx, _ = sql("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                 f"WHERE table_schema='{DB}' AND index_name LIKE 'idx_%' OR "
                 f"(table_schema='{DB}' AND index_name LIKE 'ft_%')")
    idx_cnt = idx.strip().split("\n")[-1]

    params = resolve_params()

    W = 76
    print()
    print(f"{C['cyan']}╔{'═' * W}╗{C['reset']}")
    title = "Growing Pots · 인덱스 성능 측정"
    tag = f"[ {label} ]"
    inner = f" {C['bold']}{title}{C['reset']}" + " " * max(0, W - dwidth(title) - dwidth(tag) - 2) + f"{C['yellow']}{tag}{C['reset']}"
    print(f"{C['cyan']}║{C['reset']}{inner}{C['cyan']}║{C['reset']}")
    print(f"{C['cyan']}╚{'═' * W}╝{C['reset']}")
    print(f"  {C['dim']}DB{C['reset']} {DB}   {C['dim']}MySQL{C['reset']} {version}   "
          f"{C['dim']}추가 인덱스{C['reset']} {idx_cnt}개")
    print(f"  {C['dim']}데이터{C['reset']} course {fmt_int(c_cnt)}행 · student_course {fmt_int(sc_cnt)}행   "
          f"{C['dim']}측정{C['reset']} {RUNS}회 (워밍업 {WARMUP}회)")
    print(f"  {C['dim']}파라미터{C['reset']} student_profile_id={params['profile']} · "
          f"department_id={params['dept']} · division_id={params['division']}")
    print()

    results = {}
    for qid, title, method, q_tpl in QUERIES:
        q = q_tpl.format(**params)
        plan, perr = explain_plan(q)
        stats, merr = measure(q)

        print(f"{C['blue']}┌─ {C['bold']}{qid}{C['reset']}{C['blue']} · {title}{C['reset']}")
        print(f"{C['blue']}│{C['reset']}  {C['dim']}{method}{C['reset']}")

        if perr or merr:
            raw = (perr or merr).split("\n")[0]
            if "FULLTEXT" in raw:
                reason = "Full-Text 인덱스 없음 — indexes_add.sql 적용 후 측정됨"
            else:
                reason = raw[:64]
            print(f"{C['blue']}│{C['reset']}  {C['yellow']}건너뜀{C['reset']} {C['dim']}{reason}{C['reset']}")
            print(f"{C['blue']}└{C['reset']}")
            print()
            results[qid] = {"skipped": reason}
            continue

        read = handler_rows(q)
        t = stats["times"]
        results[qid] = {
            "title": title, "plan": plan, "rows_read": read,
            "rows_returned": stats["rows_returned"],
            "min": min(t), "p50": pct(t, 50), "p95": pct(t, 95),
        }

        for i, row in enumerate(plan):
            label = "실행계획" if i == 0 else "        "
            # 작은 테이블(수십 행)의 풀스캔은 최적이므로 빨간색으로 겁주지 않는다.
            big_scan = row["type"] == "ALL" and float(row.get("rows") or 0) > 100
            type_col = C["red"] if big_scan else (C["yellow"] if row["type"] == "ALL" else C["green"])
            key_disp = row["key"] if row["key"] not in ("NULL", "") else f"{C['dim']}—{C['reset']}"
            print(f"{C['blue']}│{C['reset']}  {label}   {C['dim']}{row['table']:<4}{C['reset']}"
                  f" type {type_col}{pad(row['type'], 12)}{C['reset']}"
                  f" rows {fmt_int(row['rows']):>6}  key {key_disp}")
            if row["extra"]:
                print(f"{C['blue']}│{C['reset']}              {C['dim']}{row['extra'][:62]}{C['reset']}")
        # COUNT 집계는 결과가 항상 1행이라 "반환 행"이 스캔 규모를 말해주지 않는다.
        # LIMIT 없는 count는 조건에 맞는 행 전부를 훑어야 끝나므로, 스캔한 행 수 자체가 비용이다.
        is_count = q.strip().upper().startswith("SELECT COUNT")
        if is_count:
            print(f"{C['blue']}│{C['reset']}  스캔       {C['bold']}{fmt_int(read)}{C['reset']}행 훑음 "
                  f"{C['dim']}(COUNT 집계 — LIMIT 없이 조건에 맞는 행 전부){C['reset']}")
        else:
            eff = ""
            if read and stats["rows_returned"]:
                ratio = stats["rows_returned"] / read * 100
                eff = f"  (효율 {ratio:.1f}%)"
            print(f"{C['blue']}│{C['reset']}  스캔       {C['bold']}{fmt_int(read)}{C['reset']}행 읽어 "
                  f"{fmt_int(stats['rows_returned'])}행 반환{C['dim']}{eff}{C['reset']}")
        print(f"{C['blue']}│{C['reset']}  응답시간   min {min(t):.2f}ms   "
              f"{C['bold']}p50 {pct(t, 50):.2f}ms{C['reset']}   p95 {pct(t, 95):.2f}ms")
        print(f"{C['blue']}└{C['reset']}")
        print()

    print(f"{C['mag']}┌─ {C['bold']}W1{C['reset']}{C['mag']} · 쓰기 비용 (student_course 500행 INSERT){C['reset']}")
    wd, werr = measure_write(params)
    if wd:
        results["W1"] = {"title": "500행 INSERT", "min": min(wd), "p50": pct(wd, 50), "p95": pct(wd, 95)}
        print(f"{C['mag']}│{C['reset']}  {C['dim']}인덱스는 읽기를 빠르게 하는 대신 쓰기를 느리게 한다{C['reset']}")
        print(f"{C['mag']}│{C['reset']}  소요시간   min {min(wd):.1f}ms   {C['bold']}p50 {pct(wd, 50):.1f}ms{C['reset']}"
              f"   p95 {pct(wd, 95):.1f}ms")
    else:
        print(f"{C['mag']}│{C['reset']}  {C['red']}측정 실패{C['reset']} {C['dim']}{werr}{C['reset']}")
    print(f"{C['mag']}└{C['reset']}")
    print()

    out_file = RESULTS / f"{label}.json"
    out_file.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  {C['dim']}저장됨 → {out_file.relative_to(HERE.parent.parent)}{C['reset']}")

    other = "before" if label == "after" else ("after" if label == "before" else None)
    if other and (RESULTS / f"{other}.json").exists():
        base = json.loads((RESULTS / f"{other}.json").read_text(encoding="utf-8"))
        b, a = (base, results) if label == "after" else (results, base)
        print_compare(b, a)


def print_compare(before, after):
    W = 76
    print()
    print(f"{C['cyan']}╔{'═' * W}╗{C['reset']}")
    t = "before → after 비교"
    print(f"{C['cyan']}║{C['reset']} {C['bold']}{t}{C['reset']}{' ' * (W - len(t) - 2)}{C['cyan']}║{C['reset']}")
    print(f"{C['cyan']}╚{'═' * W}╝{C['reset']}")
    print()
    print(f"  {'':<5}{pad('항목', 26)}{' ' * (22 - dwidth('읽은 행'))}읽은 행{' ' * (22 - dwidth('p50 응답'))}p50 응답")
    print(f"  {C['dim']}{'─' * (W - 2)}{C['reset']}")

    for qid in list(before.keys()) + [k for k in after if k not in before]:
        b, a = before.get(qid, {}), after.get(qid, {})
        if b.get("skipped") and a.get("skipped"):
            continue
        title = (a.get("title") or b.get("title") or qid)[:24]

        def cell(bv, av, unit, lower_better=True, intfmt=False):
            if bv is None and av is None:
                return f"{C['dim']}{'—':>20}{C['reset']}"
            if bv is None:
                return f"{C['dim']}신규{C['reset']} {av:,.0f}{unit}" if intfmt else f"{C['dim']}신규{C['reset']} {av:.2f}{unit}"
            if av is None:
                return f"{C['dim']}제거{C['reset']}"
            fb = f"{bv:,.0f}" if intfmt else f"{bv:.2f}"
            fa = f"{av:,.0f}" if intfmt else f"{av:.2f}"
            if bv > 0:
                delta = (av - bv) / bv * 100
                good = delta < -5 if lower_better else delta > 5
                bad = delta > 5 if lower_better else delta < -5
                col = C["green"] if good else (C["red"] if bad else C["dim"])
                sign = "+" if delta >= 0 else ""
                return f"{fb} → {C['bold']}{fa}{C['reset']}{unit} {col}{sign}{delta:.0f}%{C['reset']}"
            return f"{fb} → {fa}{unit}"

        rows_cell = cell(b.get("rows_read"), a.get("rows_read"), "", True, True)
        time_cell = cell(b.get("p50"), a.get("p50"), "ms", True, False)
        print(f"  {C['bold']}{qid:<5}{C['reset']}{pad(title, 26)}"
              f"{' ' * max(0, 22 - dwidth(rows_cell))}{rows_cell}"
              f"{' ' * max(0, 22 - dwidth(time_cell))}{time_cell}")
    print()
    print(f"  {C['dim']}읽은 행 수가 응답시간보다 안정적인 지표다. 머신 부하에 흔들리지 않는다.{C['reset']}")
    print()


def strip_ansi(s):
    return re.sub(r"\033\[[0-9;]*m", "", s)


if __name__ == "__main__":
    main()
