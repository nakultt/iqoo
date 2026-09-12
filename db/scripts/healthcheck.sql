-- =============================================================================
-- VeriTransit — database health check.
--   psql -d veritransit -f db/scripts/healthcheck.sql
-- Every line should read PASS. Run it after setup, after a restart, or from
-- another device to prove remote access works end to end.
-- =============================================================================
\pset pager off
\pset format aligned
\timing off

WITH checks AS (
  SELECT 1 AS n, 'schema: 23 tables present' AS item,
         (SELECT count(*) FROM information_schema.tables
           WHERE table_schema='public' AND table_type='BASE TABLE') AS got, 23::bigint AS want
  UNION ALL SELECT 2, 'schema: 6 read-model views',
         (SELECT count(*) FROM information_schema.views WHERE table_schema='public'), 6
  UNION ALL SELECT 3, 'data: 5 shipments seeded',
         (SELECT count(*) FROM shipments), 5
  UNION ALL SELECT 4, 'data: 3547 packages',
         (SELECT count(*) FROM packages), 3547
  UNION ALL SELECT 5, 'data: every package has a live signed label',
         (SELECT count(*) FROM packages p
           WHERE NOT EXISTS (SELECT 1 FROM labels l
                              WHERE l.package_id=p.id AND l.superseded_at IS NULL)), 0
  UNION ALL SELECT 6, 'integrity: no inner box orphaned from its master',
         (SELECT count(*) FROM packages c WHERE c.parent_code IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM packages m WHERE m.package_code=c.parent_code)), 0
  UNION ALL SELECT 7, 'integrity: no scan references an unknown package',
         (SELECT count(*) FROM scan_events s
            WHERE NOT EXISTS (SELECT 1 FROM packages p WHERE p.package_code=s.package_code)), 0
  UNION ALL SELECT 8, 'integrity: scan client_event_ids are unique',
         (SELECT count(*) FROM (SELECT client_event_id FROM scan_events
                                 GROUP BY 1 HAVING count(*)>1) d), 0
  UNION ALL SELECT 9, 'audit: hash chain intact',
         (SELECT CASE WHEN ok THEN 0 ELSE 1 END FROM audit_verify_chain()), 0
  UNION ALL SELECT 10, 'finance: SHP-2026-090231 holds exactly ₹1,04,880',
         (SELECT (held_value*100)::bigint FROM finance_terms f
            JOIN shipments s ON s.id=f.shipment_id WHERE s.ref='SHP-2026-090231'), 10488000
  UNION ALL SELECT 11, 'match: PO-vs-invoice delta is ₹87,400',
         (SELECT (delta_value*100)::bigint FROM v_four_way_match
           WHERE shipment_ref='SHP-2026-090231' AND mismatch_code='QTY_MISMATCH'), 8740000
  UNION ALL SELECT 12, 'nesting: one master short by one inner unit',
         (SELECT count(*) FROM v_master_boxes
           WHERE shipment_id=(SELECT id FROM shipments WHERE ref='SHP-2026-090231')
             AND NOT fully_verified), 1
  UNION ALL SELECT 13, 'risk: every score carries explaining factors',
         (SELECT count(*) FROM risk_scores WHERE jsonb_array_length(factors)=0), 0
  UNION ALL SELECT 14, 'policy: no active policy lacks human approval',
         (SELECT count(*) FROM policies WHERE active AND approved_by IS NULL), 0
)
SELECT n, CASE WHEN got = want THEN 'PASS' ELSE 'FAIL' END AS result,
       item, got, want
FROM checks ORDER BY n;

\echo ''
\echo '── if every row above says PASS the database is healthy ──'
