package com.erfeamor.cvdomain.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Records the SQL Hibernate actually issues, so ordering tests can assert the sort keys the
 * database was asked for rather than only the row order it happened to return.
 *
 * <p>Why row order alone is not evidence (T-105, T-109): H2 stores rows in a primary-key B-tree,
 * so a tie group comes back in {@code id} ascending order whatever the query says — under a bare
 * primary sort key and under no {@code ORDER BY} at all — and InnoDB usually does the same for a
 * small scan. No row-order assertion in a {@code @DataJpaTest} can go red against a missing
 * tiebreaker, even with ids assigned against insertion order. Only the emitted SQL distinguishes a
 * declared tiebreak from an incidental one.
 *
 * <p>Usage: {@code @Import(CapturedSql.class)} on a {@code @DataJpaTest}, and
 * {@link #clear()} in a {@code @BeforeEach}. Every class importing it resolves to the same cached
 * Spring context, so one inspector instance serves them all; the per-test {@code clear()} is what
 * keeps one class's (or one test's) statements out of the next one's capture.
 *
 * <p>{@link #STATEMENTS} is process-global mutable state: no test using it may run under parallel
 * test execution. None is configured today (no {@code junit-platform.properties}, no surefire
 * {@code parallel} setting); enabling it would interleave unrelated statements into the capture
 * and make these assertions flake as if Hibernate had changed.
 */
@TestConfiguration
public class CapturedSql implements HibernatePropertiesCustomizer, StatementInspector {

    /** Every statement Hibernate prepared since the last {@link #clear()}, in issue order. */
    public static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, this);
    }

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    /** Forgets everything captured so far. Call from {@code @BeforeEach}. */
    public static void clear() {
        STATEMENTS.clear();
    }

    /**
     * The {@code ORDER BY} clause — lower-cased, from {@code order by} to the end of the statement —
     * of the <em>last</em> SELECT issued against {@code table}. The last, not any: an earlier
     * lookup against the same table must not satisfy an assertion about the query under test.
     *
     * @throws AssertionError if no such SELECT was captured, or it carries no {@code ORDER BY}
     */
    public static String orderByOfLastSelectFrom(String table) {
        String from = "from " + table.toLowerCase(Locale.ROOT) + " ";
        List<String> snapshot;
        synchronized (STATEMENTS) {
            snapshot = new ArrayList<>(STATEMENTS);
        }
        String select = snapshot.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("select") && sql.contains(from))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no select against " + table
                        + " was issued; captured: " + snapshot));
        int orderBy = select.lastIndexOf("order by");
        if (orderBy < 0) {
            throw new AssertionError("select against " + table + " has no ORDER BY: " + select);
        }
        return select.substring(orderBy).trim();
    }
}
