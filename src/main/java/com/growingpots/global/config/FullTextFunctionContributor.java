package com.growingpots.global.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.type.StandardBasicTypes;

// MATCH ... AGAINST는 JPQL/Criteria 표준에 없어서 커스텀 함수로 등록해야 Specification에서
// 쓸 수 있다. META-INF/services/org.hibernate.boot.model.FunctionContributor로 등록된다.
public class FullTextFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        var doubleType = functionContributions.getTypeConfiguration()
                .getBasicTypeRegistry().resolve(StandardBasicTypes.DOUBLE);
        if (functionContributions.getDialect() instanceof MySQLDialect) {
            functionContributions.getFunctionRegistry().registerPattern(
                    "match_against",
                    "match (?1, ?2) against (?3 in boolean mode)",
                    doubleType);
        } else {
            // H2(테스트)에는 MATCH 문법이 없어 LIKE 부분 일치로 대체한다. 호출부가 감싼 구문 검색용
            // 큰따옴표를 벗겨서 비교한다. ngram Full-Text와 LIKE의 결과 동등성은 실제 MySQL에서
            // docs/perf/verify_fulltext_equivalence.py로 검증했다.
            functionContributions.getFunctionRegistry().registerPattern(
                    "match_against",
                    "case when ?1 like concat('%', replace(?3, '\"', ''), '%')"
                            + " or ?2 like concat('%', replace(?3, '\"', ''), '%')"
                            + " then 1.0 else 0.0 end",
                    doubleType);
        }
    }
}
