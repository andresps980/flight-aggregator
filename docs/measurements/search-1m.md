# Search performance at 1M rows — measured run (spec 005)

Output of `SearchPerformanceIT` (`./mvnw -pl flight-aggregator verify -Pslow-tests -Dit.test=SearchPerformanceIT`),
2026-11-06, Apple M1 Max, Docker with 6 CPUs / 16 GB, `postgres:17` and `redis:8` Testcontainers, 1,000,006
gds-mock-shaped rows over 7 days written through the real ingestion store. Summarised in
[ADR-0020](../adr/0020-search-indexes.md) and [SOLUTION.md](../../SOLUTION.md#25-keyset-vs-offset-measured).

## Bulk load (COPY, 1M rows, 7 days)

| Indexes | First load | Replacement |
|---|---:|---:|
| PK, unique key, departure_date | 12.1 s | |
| + search indexes (itinerary_price_id_idx, itinerary_route_price_id_idx) | 17.9 s | 32.6 s |

Search index definitions:

```sql
CREATE INDEX itinerary_price_id_idx ON public.itinerary USING btree (final_price, id);
CREATE INDEX itinerary_route_price_id_idx ON public.itinerary USING btree (origin, destination, final_price, id);
```

Busiest route FRA-JNB: 993 rows; busiest carrier QR.

## Plans (EXPLAIN (ANALYZE, BUFFERS), first page of 20)

### none

```
Limit  (cost=0.58..12.41 rows=21 width=462) (actual time=0.056..0.194 rows=21.00 loops=1)
  Buffers: shared hit=15 read=23 dirtied=1
  ->  Nested Loop  (cost=0.58..563053.81 rows=1000347 width=462) (actual time=0.055..0.192 rows=21.00 loops=1)
        Buffers: shared hit=15 read=23 dirtied=1
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..538071.48 rows=1000347 width=413) (actual time=0.039..0.163 rows=21.00 loops=1)
              Filter: (departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone)
              Index Searches: 1
              Buffers: shared hit=2 read=22
        ->  Memoize  (cost=0.16..0.18 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=13 read=1 dirtied=1
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.002..0.002 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=13 read=1 dirtied=1
Planning:
  Buffers: shared hit=100
Planning Time: 0.258 ms
Execution Time: 0.217 ms
```

### maxPrice

```
Limit  (cost=0.58..36.47 rows=21 width=462) (actual time=0.013..0.038 rows=21.00 loops=1)
  Buffers: shared hit=38
  ->  Nested Loop  (cost=0.58..504576.37 rows=295291 width=462) (actual time=0.013..0.036 rows=21.00 loops=1)
        Buffers: shared hit=38
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..497200.94 rows=295291 width=413) (actual time=0.007..0.018 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: (departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone)
              Index Searches: 1
              Buffers: shared hit=24
        ->  Memoize  (cost=0.16..0.18 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=14
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=14
Planning:
  Buffers: shared hit=3
Planning Time: 0.108 ms
Execution Time: 0.051 ms
```

### date

```
Limit  (cost=0.57..78.71 rows=21 width=462) (actual time=0.008..0.739 rows=21.00 loops=1)
  Buffers: shared hit=26 read=107 written=16
  ->  Nested Loop  (cost=0.57..542403.07 rows=145784 width=462) (actual time=0.008..0.736 rows=21.00 loops=1)
        Buffers: shared hit=26 read=107 written=16
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..540572.60 rows=145784 width=413) (actual time=0.005..0.723 rows=21.00 loops=1)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 106
              Index Searches: 1
              Buffers: shared hit=24 read=107 written=16
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.002..0.002 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.049 ms
Execution Time: 0.751 ms
```

### date + maxPrice

```
Limit  (cost=0.57..243.83 rows=21 width=462) (actual time=0.019..0.104 rows=21.00 loops=1)
  Buffers: shared hit=133
  ->  Nested Loop  (cost=0.57..498485.33 rows=43034 width=462) (actual time=0.019..0.102 rows=21.00 loops=1)
        Buffers: shared hit=133
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..497939.24 rows=43034 width=413) (actual time=0.013..0.090 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 106
              Index Searches: 1
              Buffers: shared hit=131
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.003..0.004 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.086 ms
Execution Time: 0.121 ms
```

### carrier

```
Limit  (cost=0.58..264.20 rows=21 width=462) (actual time=0.011..2.438 rows=21.00 loops=1)
  Buffers: shared hit=148 read=305 written=197
  ->  Nested Loop  (cost=0.58..541651.45 rows=43148 width=462) (actual time=0.011..2.436 rows=21.00 loops=1)
        Buffers: shared hit=148 read=305 written=197
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..540572.60 rows=43148 width=413) (actual time=0.006..2.415 rows=21.00 loops=1)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 410
              Index Searches: 1
              Buffers: shared hit=134 read=305 written=197
        ->  Memoize  (cost=0.16..0.18 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=14
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=14
Planning Time: 0.084 ms
Execution Time: 2.452 ms
```

### origin

```
Limit  (cost=0.57..376.60 rows=21 width=462) (actual time=0.018..2.887 rows=21.00 loops=1)
  Buffers: shared hit=486 read=347 written=249
  ->  Nested Loop  (cost=0.57..545724.43 rows=30477 width=462) (actual time=0.018..2.885 rows=21.00 loops=1)
        Buffers: shared hit=486 read=347 written=249
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..540572.60 rows=30477 width=413) (actual time=0.014..2.854 rows=21.00 loops=1)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (origin = 'FRA'::bpchar))
              Rows Removed by Filter: 758
              Index Searches: 1
              Buffers: shared hit=444 read=347 written=249
        ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Index Cond: (departure_date = i.departure_date)
              Index Searches: 21
              Buffers: shared hit=42
Planning Time: 0.093 ms
Execution Time: 2.901 ms
```

### origin + maxPrice

```
Limit  (cost=0.57..1166.45 rows=21 width=462) (actual time=0.022..0.507 rows=21.00 loops=1)
  Buffers: shared hit=833
  ->  Nested Loop  (cost=0.57..499493.85 rows=8997 width=462) (actual time=0.022..0.505 rows=21.00 loops=1)
        Buffers: shared hit=833
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..497939.24 rows=8997 width=413) (actual time=0.017..0.485 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (origin = 'FRA'::bpchar))
              Rows Removed by Filter: 758
              Index Searches: 1
              Buffers: shared hit=791
        ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Index Cond: (departure_date = i.departure_date)
              Index Searches: 21
              Buffers: shared hit=42
Planning Time: 0.121 ms
Execution Time: 0.519 ms
```

### carrier + maxPrice

```
Limit  (cost=0.57..825.14 rows=21 width=462) (actual time=0.012..0.187 rows=21.00 loops=1)
  Buffers: shared hit=481
  ->  Nested Loop  (cost=0.57..500120.18 rows=12737 width=462) (actual time=0.011..0.185 rows=21.00 loops=1)
        Buffers: shared hit=481
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..497939.24 rows=12737 width=413) (actual time=0.009..0.170 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 410
              Index Searches: 1
              Buffers: shared hit=439
        ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Index Cond: (departure_date = i.departure_date)
              Index Searches: 21
              Buffers: shared hit=42
Planning Time: 0.072 ms
Execution Time: 0.196 ms
```

### origin + carrier + maxPrice

```
Limit  (cost=0.42..27161.25 rows=21 width=462) (actual time=0.169..91.880 rows=21.00 loops=1)
  Buffers: shared hit=3640 read=15933 written=3371
  ->  Nested Loop  (cost=0.42..501829.02 rows=388 width=462) (actual time=0.169..91.873 rows=21.00 loops=1)
        Join Filter: (i.departure_date = s.departure_date)
        Rows Removed by Join Filter: 80
        Buffers: shared hit=3640 read=15933 written=3371
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..498677.54 rows=388 width=413) (actual time=0.158..91.799 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (origin = 'FRA'::bpchar) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 19334
              Index Searches: 1
              Buffers: shared hit=3639 read=15933 written=3371
        ->  Materialize  (cost=0.00..18.10 rows=540 width=53) (actual time=0.001..0.001 rows=4.81 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=1
              ->  Seq Scan on day_snapshot s  (cost=0.00..15.40 rows=540 width=53) (actual time=0.007..0.011 rows=7.00 loops=1)
                    Buffers: shared hit=1
Planning Time: 0.064 ms
Execution Time: 91.899 ms
```

### route

```
Limit  (cost=0.58..86.48 rows=21 width=462) (actual time=0.042..0.072 rows=21.00 loops=1)
  Buffers: shared hit=37 read=1
  ->  Nested Loop  (cost=0.58..3816.66 rows=933 width=462) (actual time=0.041..0.070 rows=21.00 loops=1)
        Buffers: shared hit=37 read=1
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3791.62 rows=933 width=413) (actual time=0.031..0.048 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar))
              Filter: (departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone)
              Index Searches: 1
              Buffers: shared hit=23 read=1
        ->  Memoize  (cost=0.16..0.23 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=14
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.22 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=14
Planning Time: 0.127 ms
Execution Time: 0.087 ms
```

### route + maxPrice

```
Limit  (cost=0.58..87.01 rows=21 width=462) (actual time=0.015..0.034 rows=21.00 loops=1)
  Buffers: shared hit=38
  ->  Nested Loop  (cost=0.58..1132.35 rows=275 width=462) (actual time=0.014..0.032 rows=21.00 loops=1)
        Buffers: shared hit=38
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..1122.77 rows=275 width=413) (actual time=0.010..0.017 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar) AND (final_price <= 200.00))
              Filter: (departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone)
              Index Searches: 1
              Buffers: shared hit=24
        ->  Memoize  (cost=0.16..0.35 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=14
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.34 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=14
Planning Time: 0.078 ms
Execution Time: 0.045 ms
```

### route + date + maxPrice

```
Limit  (cost=0.57..594.64 rows=21 width=462) (actual time=0.013..0.422 rows=21.00 loops=1)
  Buffers: shared hit=54 read=76
  ->  Nested Loop  (cost=0.57..1132.13 rows=40 width=462) (actual time=0.013..0.420 rows=21.00 loops=1)
        Buffers: shared hit=54 read=76
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..1123.46 rows=40 width=413) (actual time=0.010..0.410 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar) AND (final_price <= 200.00))
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 103
              Index Searches: 1
              Buffers: shared hit=52 read=76
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.002..0.002 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.055 ms
Execution Time: 0.432 ms
```

### route + date

```
Limit  (cost=0.57..587.84 rows=21 width=462) (actual time=0.013..0.058 rows=21.00 loops=1)
  Buffers: shared hit=130
  ->  Nested Loop  (cost=0.57..3803.82 rows=136 width=462) (actual time=0.012..0.056 rows=21.00 loops=1)
        Buffers: shared hit=130
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3793.95 rows=136 width=413) (actual time=0.009..0.047 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar))
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 103
              Index Searches: 1
              Buffers: shared hit=128
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.002..0.002 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.065 ms
Execution Time: 0.067 ms
```

### route + carrier

```
Limit  (cost=0.58..1998.24 rows=21 width=462) (actual time=0.031..1.570 rows=21.00 loops=1)
  Buffers: shared hit=251 read=295
  ->  Nested Loop  (cost=0.58..3805.65 rows=40 width=462) (actual time=0.031..1.567 rows=21.00 loops=1)
        Buffers: shared hit=251 read=295
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3793.95 rows=40 width=413) (actual time=0.028..1.551 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar))
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 503
              Index Searches: 1
              Buffers: shared hit=239 read=295
        ->  Memoize  (cost=0.16..1.38 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 15  Misses: 6  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=12
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..1.37 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=6)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 6
                    Buffers: shared hit=12
Planning Time: 0.066 ms
Execution Time: 1.581 ms
```

### route, maxPrice above all (worst realistic)

```
Limit  (cost=0.58..86.53 rows=21 width=462) (actual time=0.020..0.050 rows=21.00 loops=1)
  Buffers: shared hit=38
  ->  Nested Loop  (cost=0.58..3818.99 rows=933 width=462) (actual time=0.020..0.048 rows=21.00 loops=1)
        Buffers: shared hit=38
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3793.95 rows=933 width=413) (actual time=0.014..0.030 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar) AND (final_price <= 999999.00))
              Filter: (departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone)
              Index Searches: 1
              Buffers: shared hit=24
        ->  Memoize  (cost=0.16..0.23 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=14
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.22 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=14
Planning:
  Buffers: shared hit=3 read=1
Planning Time: 0.130 ms
Execution Time: 0.064 ms
```

## Keyset vs OFFSET, server side (ms, median / p95 of 30 runs)

| Filters | Matching rows | Page | Keyset | OFFSET |
|---|---:|---:|---:|---:|
| none | 1,000,006 | 1 | 0.75 / 1.24 | 0.61 / 0.76 |
| none | 1,000,006 | 1,000 | 0.55 / 0.75 | 50.30 / 68.81 |
| maxPrice | 294,289 | 1 | 0.45 / 0.65 | 0.46 / 0.53 |
| maxPrice | 294,289 | 1,000 | 0.51 / 0.71 | 52.64 / 78.16 |
| date | 142,858 | 1 | 0.50 / 0.60 | 0.51 / 0.62 |
| date | 142,858 | 1,000 | 0.47 / 0.63 | 63.68 / 65.51 |
| date + maxPrice | 41,799 | 1 | 0.53 / 1.47 | 0.40 / 0.57 |
| date + maxPrice | 41,799 | 1,000 | 0.52 / 1.46 | 41.15 / 42.37 |
| carrier | 42,082 | 1 | 0.64 / 0.76 | 0.66 / 0.92 |
| carrier | 42,082 | 1,000 | 0.76 / 0.94 | 159.11 / 176.64 |
| origin | 29,375 | 1 | 0.68 / 0.92 | 0.67 / 0.99 |
| origin | 29,375 | 1,000 | 0.89 / 1.78 | 94.91 / 104.69 |
| origin + maxPrice | 8,596 | 1 | 0.87 / 1.21 | 0.97 / 1.38 |
| origin + maxPrice | 8,596 | 430 | 0.73 / 0.93 | 15.44 / 16.63 |
| carrier + maxPrice | 12,397 | 1 | 0.76 / 1.01 | 0.75 / 0.97 |
| carrier + maxPrice | 12,397 | 620 | 0.99 / 1.22 | 174.92 / 192.35 |
| origin + carrier + maxPrice | 365 | 1 | 48.71 / 65.50 | 51.70 / 72.76 |
| origin + carrier + maxPrice | 365 | 19 | 0.76 / 1.05 | 7.88 / 9.64 |
| route | 993 | 1 | 0.59 / 0.74 | 0.68 / 1.04 |
| route | 993 | 50 | 0.92 / 1.16 | 2.28 / 2.66 |
| route + maxPrice | 295 | 1 | 0.67 / 0.88 | 0.63 / 0.76 |
| route + maxPrice | 295 | 15 | 0.47 / 0.62 | 1.07 / 1.26 |
| route + date + maxPrice | 39 | 1 | 0.60 / 0.77 | 0.48 / 1.23 |
| route + date + maxPrice | 39 | 2 | 0.63 / 0.92 | 0.52 / 0.99 |
| route + date | 127 | 1 | 0.43 / 0.63 | 0.49 / 0.66 |
| route + date | 127 | 7 | 0.40 / 0.64 | 3.05 / 3.19 |
| route + carrier | 35 | 1 | 0.69 / 0.95 | 0.62 / 0.91 |
| route + carrier | 35 | 2 | 0.59 / 0.96 | 1.27 / 1.59 |
| route, maxPrice above all (worst realistic) | 993 | 1 | 0.57 / 0.77 | 0.58 / 0.70 |
| route, maxPrice above all (worst realistic) | 993 | 50 | 0.53 / 0.91 | 2.14 / 2.98 |

## API latency, uncached vs cached (ms, median / p95 of 30 runs, client side)

| Request | Uncached | Cached |
|---|---:|---:|
| `/api/v1/itineraries?limit=20` | 8.02 / 9.07 | 5.41 / 7.41 |
| `/api/v1/itineraries?limit=20&maxPrice=200` | 7.65 / 9.53 | 4.92 / 8.92 |
| `/api/v1/itineraries?limit=20&date=2026-11-08` | 5.69 / 8.24 | 3.56 / 5.50 |
| `/api/v1/itineraries?limit=20&date=2026-11-08&maxPrice=200` | 5.52 / 11.99 | 3.69 / 9.17 |
| `/api/v1/itineraries?limit=20&carrier=QR` | 4.36 / 5.71 | 2.80 / 3.54 |
| `/api/v1/itineraries?limit=20&origin=FRA` | 4.63 / 8.28 | 2.80 / 5.50 |
| `/api/v1/itineraries?limit=20&origin=FRA&maxPrice=200` | 5.31 / 8.35 | 3.11 / 6.01 |
| `/api/v1/itineraries?limit=20&carrier=QR&maxPrice=200` | 4.56 / 5.49 | 2.80 / 3.66 |
| `/api/v1/itineraries?limit=20&origin=FRA&carrier=QR&maxPrice=200` | 55.82 / 66.63 | 3.04 / 3.48 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB` | 3.88 / 4.53 | 2.48 / 2.82 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&maxPrice=200` | 3.52 / 5.02 | 2.28 / 2.98 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&date=2026-11-08&maxPrice=200` | 3.90 / 5.42 | 2.51 / 3.50 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&date=2026-11-08` | 2.57 / 4.26 | 1.83 / 2.08 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&carrier=QR` | 5.02 / 6.07 | 2.66 / 3.49 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&maxPrice=999999` | 2.91 / 4.65 | 1.87 / 2.31 |
| `/api/v1/catalog` | 419.66 / 461.85 | 1.40 / 1.72 |


## Addendum, 2026-11-07: the last page

`SearchPerformanceIT` now also times the **last page** of the unfiltered result (the worst case for `OFFSET`, which
reads and discards every matching row first). Re-run on the same machine and setup; the unfiltered rows of that run
(ms, median / p95 of 30 runs):

| Filters | Matching rows | Page | Keyset | OFFSET |
|---|---:|---:|---:|---:|
| none | 1,000,006 | 1 | 0.74 / 1.26 | 0.58 / 0.76 |
| none | 1,000,006 | 1,000 | 0.58 / 0.73 | 47.35 / 66.13 |
| none | 1,000,006 | 50,001 (last) | **0.51 / 0.66** | **1116.75 / 1128.32** |

Every other figure of the re-run is within run-to-run noise of the tables above (e.g. `COPY` with the search indexes
18.1 s first load / 30.4 s replacement, the catalog 449 ms uncached / 1.43 ms cached), so they are not repeated.
