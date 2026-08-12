#!/usr/bin/env python3
"""LIKE 검색과 Full-Text(ngram) 검색이 같은 결과를 주는지 검증한다.

    python3 docs/perf/verify_fulltext_equivalence.py

Full-Text는 count 쿼리 비용을 없애주지만(02-2 측정 결과), 결과 집합이 다르면
"빠른데 틀린 검색"을 배포하는 꼴이다. 이 스크립트는 course_id 집합을 직접
비교해서 둘이 정확히 같은 행을 찾는지 확인한다.

검증 대상 (실제 데이터 특성을 반영해 고른 케이스)
- 일반 키워드: 여러 과목에 흔히 나오는 substring
- 단어 경계 없는 substring: ngram이 어절 중간도 찾는지
- 1글자 검색어: ngram_token_size 기본값(2) 때문에 색인 토큰이 없어 항상 0건인 알려진
  한계 — 애플리케이션이 2글자 미만 검색어를 400으로 차단하는 근거다
- course_code 검색어: 복합 인덱스 ft_course_name_code가 name과 course_code를 함께
  커버하므로 학수번호 검색도 LIKE와 완전히 같아야 한다 (숫자, 영문+숫자 경계 포함)
- 존재하지 않는 검색어: 둘 다 0건이어야 함
"""

import os
import re
import subprocess
import sys
from pathlib import Path

DB = os.environ.get("DB_NAME", "growingpots_perf")
USER = os.environ.get("DB_USERNAME", "root")
HERE = Path(__file__).resolve().parent

C = {
    "reset": "\033[0m", "dim": "\033[2m", "bold": "\033[1m",
    "green": "\033[32m", "yellow": "\033[33m", "red": "\033[31m", "cyan": "\033[36m",
}
if not sys.stdout.isatty() or os.environ.get("NO_COLOR"):
    C = {k: "" for k in C}


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


def sql(query):
    cmd = ["mysql", "-u", USER, f"-p{PW}", DB, "--batch", "--raw", "-e", query]
    r = subprocess.run(cmd, capture_output=True, text=True)
    err = "\n".join(l for l in r.stderr.splitlines() if "Using a password" not in l)
    return r.stdout, err


def ids(query):
    out, err = sql(query)
    if err:
        return None, err
    lines = out.strip().split("\n")[1:]  # 헤더 제외
    return set(int(x) for x in lines if x), None


CASES = [
    # (라벨, 검색어, 종류)
    ("디자인", "디자인", "일반"),
    ("영화", "영화", "일반"),
    ("드로잉", "드로잉", "일반 (3글자, 정확히 한 단어)"),
    ("실습", "실습", "일반 (흔한 접미어)"),
    ("자인", "자인", "단어 중간 substring (디자인의 뒤 2글자)"),
    ("영상프로젝트", "영상프로젝트", "복합어 (여러 과목명의 앞부분과 일치)"),
    ("웹", "웹", "1글자 — ngram_token_size(2) 미만, 앱에서 400 차단"),
    ("공학수학", "공학수학", "정확히 한 과목명과 일치"),
    ("존재하지않는검색어", "존재하지않는검색어", "매칭 없음 (둘 다 0건이어야 정상)"),
    ("CHE", "CHE", "course_code 영문 접두"),
    ("453", "453", "course_code 숫자 부분"),
    ("E45", "E45", "course_code 영문·숫자 경계에 걸친 substring"),
    ("03311", "03311", "course_code 5자리 (ngram 토큰 4개의 구문 검색)"),
    ("10", "10", "name·course_code 양쪽에 다 흔한 검색어"),
]


def like_query(term):
    t = term.replace("'", "''")
    return (
        f"SELECT c.id FROM course c WHERE c.school_id = 1 AND c.is_active = 1 "
        f"AND (c.name LIKE '%{t}%' OR c.course_code LIKE '%{t}%')"
    )


# 애플리케이션(CourseSpecifications.withKeyword)과 동일한 형태: 큰따옴표를 벗긴 뒤
# 구문(phrase) 검색으로 감싼다. BOOLEAN MODE 연산자(+, - 등)로 해석될 여지를 없앤다.
def ft_query(term):
    t = term.replace("'", "''").replace('"', '')
    return (
        f"SELECT c.id FROM course c WHERE c.school_id = 1 AND c.is_active = 1 "
        f"AND MATCH(c.name, c.course_code) AGAINST('\"{t}\"' IN BOOLEAN MODE)"
    )


def main():
    ft_exists, _ = sql(
        "SELECT COUNT(*) FROM information_schema.statistics "
        f"WHERE table_schema='{DB}' AND table_name='course' AND index_name='ft_course_name_code'"
    )
    if ft_exists.strip().split("\n")[-1] == "0":
        print(f"{C['red']}ft_course_name_code 인덱스가 없습니다.{C['reset']} "
              f"먼저 적용하세요: mysql -u root -p {DB} < docs/perf/indexes_add.sql")
        sys.exit(1)

    print()
    print(f"{C['cyan']}╔{'═' * 78}╗{C['reset']}")
    print(f"{C['cyan']}║{C['reset']} {C['bold']}LIKE vs Full-Text(ngram) 결과 동등성 검증{C['reset']}"
          + " " * 30 + f"{C['cyan']}║{C['reset']}")
    print(f"{C['cyan']}╚{'═' * 78}╝{C['reset']}")
    print(f"  DB {DB}   대상: name·course_code 키워드 검색")
    print()

    header = f"  {'검색어':<10}{'설명':<38}{'LIKE':>6}{'FT':>6}{'차이':>6}  판정"
    print(header)
    print(f"  {'-' * 76}")

    summary = {"동일": 0, "1글자한계(예상됨)": 0, "불일치(문제)": 0}

    for label, term, note in CASES:
        like_ids, lerr = ids(like_query(term))
        ft_ids, ferr = ids(ft_query(term))

        if lerr or ferr:
            print(f"  {label:<10}{note:<38}{C['red']}쿼리 실패: {(lerr or ferr)[:40]}{C['reset']}")
            continue

        only_like = like_ids - ft_ids
        only_ft = ft_ids - like_ids
        diff = len(only_like) + len(only_ft)

        # 1글자 검색어는 ngram_token_size(2) 미만이라 색인 토큰 자체가 없어 Full-Text가
        # 항상 0건이다. 알려진 구조적 한계이며, 애플리케이션은 2글자 미만을 400으로 차단한다.
        expected_gap = len(term) < 2 and len(ft_ids) == 0

        if diff == 0:
            verdict = f"{C['green']}✅ 동일{C['reset']}"
            summary["동일"] += 1
        elif expected_gap:
            verdict = f"{C['yellow']}⚠️  예상된 차이 (1글자){C['reset']}"
            summary["1글자한계(예상됨)"] += 1
        else:
            verdict = f"{C['red']}❌ 불일치{C['reset']}"
            summary["불일치(문제)"] += 1

        print(f"  {label:<10}{note:<38}{len(like_ids):>6}{len(ft_ids):>6}{diff:>6}  {verdict}")
        if diff > 0 and not expected_gap:
            sample_like = sorted(only_like)[:5]
            sample_ft = sorted(only_ft)[:5]
            if sample_like:
                names, _ = sql(f"SELECT name FROM course WHERE id IN ({','.join(map(str, sample_like))})")
                print(f"    {C['dim']}LIKE만 찾음: {names.strip().splitlines()[1:]}{C['reset']}")
            if sample_ft:
                names, _ = sql(f"SELECT name FROM course WHERE id IN ({','.join(map(str, sample_ft))})")
                print(f"    {C['dim']}FT만 찾음: {names.strip().splitlines()[1:]}{C['reset']}")

    print()
    print(f"  {C['bold']}요약{C['reset']}  동일 {summary['동일']}건 · "
          f"예상된 차이(1글자) {summary['1글자한계(예상됨)']}건 · "
          f"{C['red'] if summary['불일치(문제)'] else ''}불일치 {summary['불일치(문제)']}건{C['reset']}")
    print()
    if summary["불일치(문제)"] > 0:
        print(f"  {C['red']}⚠️  실제 결과 불일치가 있습니다. Full-Text 채택 전 원인을 확인하세요.{C['reset']}")
    else:
        print(f"  {C['dim']}2글자 이상 검색어는 name·course_code 모두 LIKE와 결과가 완전히 동등하다.{C['reset']}")
        print(f"  {C['dim']}1글자 검색어만 ngram 색인 토큰이 없어 검색 불가 — 애플리케이션이{C['reset']}")
        print(f"  {C['dim']}2글자 미만 검색어를 요청 단계에서 400으로 차단하는 근거다.{C['reset']}")
    print()


if __name__ == "__main__":
    main()
