# Search performance at 1M rows — measured run (spec 005)

Output of `SearchPerformanceIT` (`./mvnw -pl flight-aggregator verify -Pslow-tests -Dit.test=SearchPerformanceIT`),
2026-11-06, Apple M1 Max, Docker with 6 CPUs / 16 GB, `postgres:17` and `redis:8` Testcontainers, 1,000,006
gds-mock-shaped rows over 7 days written through the real ingestion store. Summarised in
[ADR-0020](../adr/0020-search-indexes.md) and [SOLUTION.md](../../SOLUTION.md#keyset-vs-offset-measured-spec-005).

## Bulk load (COPY, 1M rows, 7 days)

| Indexes | First load | Replacement |
|---|---:|---:|
| PK, unique key, departure_date | 13.0 s | |
| + search indexes (itinerary_price_id_idx, itinerary_route_price_id_idx) | 18.5 s | 31.0 s |

Search index definitions:

```sql
CREATE INDEX itinerary_price_id_idx ON public.itinerary USING btree (final_price, id);
CREATE INDEX itinerary_route_price_id_idx ON public.itinerary USING btree (origin, destination, final_price, id);
```

Busiest route FRA-JNB: 993 rows; busiest carrier QR.

## Plans (EXPLAIN (ANALYZE, BUFFERS), first page of 20)

### none

```
Limit  (cost=0.58..12.45 rows=21 width=463) (actual time=0.040..0.162 rows=21.00 loops=1)
  Buffers: shared hit=15 read=23 dirtied=1
  ->  Nested Loop  (cost=0.58..562573.80 rows=995487 width=463) (actual time=0.039..0.160 rows=21.00 loops=1)
        Buffers: shared hit=15 read=23 dirtied=1
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..537712.83 rows=995487 width=414) (actual time=0.023..0.132 rows=21.00 loops=1)
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
Planning Time: 0.197 ms
Execution Time: 0.178 ms
```

### maxPrice

```
Limit  (cost=0.58..36.63 rows=21 width=463) (actual time=0.007..0.026 rows=21.00 loops=1)
  Buffers: shared hit=38
  ->  Nested Loop  (cost=0.58..504235.41 rows=293801 width=463) (actual time=0.007..0.024 rows=21.00 loops=1)
        Buffers: shared hit=38
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..496897.18 rows=293801 width=414) (actual time=0.004..0.010 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: (departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone)
              Index Searches: 1
              Buffers: shared hit=24
        ->  Memoize  (cost=0.16..0.18 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
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
Planning Time: 0.071 ms
Execution Time: 0.035 ms
```

### date

```
Limit  (cost=0.57..79.50 rows=21 width=463) (actual time=0.019..0.658 rows=21.00 loops=1)
  Buffers: shared hit=26 read=107
  ->  Nested Loop  (cost=0.57..542012.63 rows=144213 width=463) (actual time=0.019..0.656 rows=21.00 loops=1)
        Buffers: shared hit=26 read=107
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..540201.79 rows=144213 width=414) (actual time=0.012..0.642 rows=21.00 loops=1)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 106
              Index Searches: 1
              Buffers: shared hit=24 read=107
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.004..0.004 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.110 ms
Execution Time: 0.672 ms
```

### date + maxPrice

```
Limit  (cost=0.57..246.37 rows=21 width=463) (actual time=0.013..0.058 rows=21.00 loops=1)
  Buffers: shared hit=133
  ->  Nested Loop  (cost=0.57..498171.95 rows=42562 width=463) (actual time=0.013..0.057 rows=21.00 loops=1)
        Buffers: shared hit=133
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..497631.76 rows=42562 width=414) (actual time=0.008..0.045 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 106
              Index Searches: 1
              Buffers: shared hit=131
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.003..0.003 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.068 ms
Execution Time: 0.069 ms
```

### carrier

```
Limit  (cost=0.58..267.16 rows=21 width=463) (actual time=0.010..1.786 rows=21.00 loops=1)
  Buffers: shared hit=148 read=305 written=12
  ->  Nested Loop  (cost=0.58..541267.96 rows=42640 width=463) (actual time=0.010..1.784 rows=21.00 loops=1)
        Buffers: shared hit=148 read=305 written=12
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..540201.79 rows=42640 width=414) (actual time=0.005..1.764 rows=21.00 loops=1)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 410
              Index Searches: 1
              Buffers: shared hit=134 read=305 written=12
        ->  Memoize  (cost=0.16..0.18 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 14  Misses: 7  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=14
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=7)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 7
                    Buffers: shared hit=14
Planning Time: 0.074 ms
Execution Time: 1.799 ms
```

### origin

```
Limit  (cost=0.57..384.83 rows=21 width=463) (actual time=0.012..2.358 rows=21.00 loops=1)
  Buffers: shared hit=487 read=346 written=99
  ->  Nested Loop  (cost=0.57..545239.91 rows=29798 width=463) (actual time=0.012..2.356 rows=21.00 loops=1)
        Buffers: shared hit=487 read=346 written=99
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..540201.79 rows=29798 width=414) (actual time=0.009..2.335 rows=21.00 loops=1)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (origin = 'FRA'::bpchar))
              Rows Removed by Filter: 758
              Index Searches: 1
              Buffers: shared hit=445 read=346 written=99
        ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..0.17 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=21)
              Index Cond: (departure_date = i.departure_date)
              Index Searches: 21
              Buffers: shared hit=42
Planning Time: 0.079 ms
Execution Time: 2.370 ms
```

### origin + carrier + maxPrice

```
Limit  (cost=0.42..27931.44 rows=21 width=463) (actual time=0.240..88.696 rows=21.00 loops=1)
  Buffers: shared hit=3646 read=15927 written=2371
  ->  Nested Loop  (cost=0.42..501428.71 rows=377 width=463) (actual time=0.240..88.688 rows=21.00 loops=1)
        Join Filter: (i.departure_date = s.departure_date)
        Rows Removed by Join Filter: 80
        Buffers: shared hit=3646 read=15927 written=2371
        ->  Index Scan using itinerary_price_id_idx on itinerary i  (cost=0.42..498366.33 rows=377 width=414) (actual time=0.228..88.619 rows=21.00 loops=1)
              Index Cond: (final_price <= 200.00)
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (origin = 'FRA'::bpchar) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 19334
              Index Searches: 1
              Buffers: shared hit=3645 read=15927 written=2371
        ->  Materialize  (cost=0.00..18.10 rows=540 width=53) (actual time=0.001..0.001 rows=4.81 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=1
              ->  Seq Scan on day_snapshot s  (cost=0.00..15.40 rows=540 width=53) (actual time=0.008..0.012 rows=7.00 loops=1)
                    Buffers: shared hit=1
Planning Time: 0.103 ms
Execution Time: 88.720 ms
```

### route

```
Limit  (cost=0.58..86.43 rows=21 width=463) (actual time=0.042..0.086 rows=21.00 loops=1)
  Buffers: shared hit=37 read=1
  ->  Nested Loop  (cost=0.58..3691.96 rows=903 width=463) (actual time=0.042..0.084 rows=21.00 loops=1)
        Buffers: shared hit=37 read=1
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3667.66 rows=903 width=414) (actual time=0.033..0.064 rows=21.00 loops=1)
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
Planning Time: 0.134 ms
Execution Time: 0.106 ms
```

### route + date

```
Limit  (cost=0.57..590.36 rows=21 width=463) (actual time=0.017..0.621 rows=21.00 loops=1)
  Buffers: shared hit=53 read=77 written=12
  ->  Nested Loop  (cost=0.57..3679.73 rows=131 width=463) (actual time=0.017..0.618 rows=21.00 loops=1)
        Buffers: shared hit=53 read=77 written=12
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3669.92 rows=131 width=414) (actual time=0.012..0.604 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar))
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (departure_date = '2026-11-08'::date))
              Rows Removed by Filter: 103
              Index Searches: 1
              Buffers: shared hit=51 read=77 written=12
        ->  Materialize  (cost=0.15..8.17 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=2
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..8.17 rows=1 width=53) (actual time=0.003..0.003 rows=1.00 loops=1)
                    Index Cond: (departure_date = '2026-11-08'::date)
                    Index Searches: 1
                    Buffers: shared hit=2
Planning Time: 0.067 ms
Execution Time: 0.633 ms
```

### route + carrier

```
Limit  (cost=0.58..1982.79 rows=21 width=463) (actual time=0.054..1.677 rows=21.00 loops=1)
  Buffers: shared hit=247 read=299 written=26
  ->  Nested Loop  (cost=0.58..3681.82 rows=39 width=463) (actual time=0.053..1.675 rows=21.00 loops=1)
        Buffers: shared hit=247 read=299 written=26
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3669.92 rows=39 width=414) (actual time=0.048..1.658 rows=21.00 loops=1)
              Index Cond: ((origin = 'FRA'::bpchar) AND (destination = 'JNB'::bpchar))
              Filter: ((departure_at > '2026-11-06 00:00:00+01'::timestamp with time zone) AND (airline = 'QR'::bpchar))
              Rows Removed by Filter: 503
              Index Searches: 1
              Buffers: shared hit=235 read=299 written=26
        ->  Memoize  (cost=0.16..1.41 rows=1 width=53) (actual time=0.000..0.000 rows=1.00 loops=21)
              Cache Key: i.departure_date
              Cache Mode: logical
              Hits: 15  Misses: 6  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=12
              ->  Index Scan using day_snapshot_pkey on day_snapshot s  (cost=0.15..1.40 rows=1 width=53) (actual time=0.001..0.001 rows=1.00 loops=6)
                    Index Cond: (departure_date = i.departure_date)
                    Index Searches: 6
                    Buffers: shared hit=12
Planning Time: 0.092 ms
Execution Time: 1.690 ms
```

### route, maxPrice above all (worst realistic)

```
Limit  (cost=0.58..86.48 rows=21 width=463) (actual time=0.020..0.051 rows=21.00 loops=1)
  Buffers: shared hit=38
  ->  Nested Loop  (cost=0.58..3694.22 rows=903 width=463) (actual time=0.019..0.049 rows=21.00 loops=1)
        Buffers: shared hit=38
        ->  Index Scan using itinerary_route_price_id_idx on itinerary i  (cost=0.42..3669.92 rows=903 width=414) (actual time=0.013..0.031 rows=21.00 loops=1)
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
Planning Time: 0.135 ms
Execution Time: 0.066 ms
```

## Keyset vs OFFSET, server side (ms, median / p95 of 30 runs)

| Filters | Matching rows | Page | Keyset | OFFSET |
|---|---:|---:|---:|---:|
| none | 1,000,006 | 1 | 0.80 / 1.20 | 0.58 / 0.77 |
| none | 1,000,006 | 1,000 | 0.54 / 0.65 | 50.66 / 62.93 |
| maxPrice | 294,289 | 1 | 0.50 / 0.60 | 0.51 / 0.65 |
| maxPrice | 294,289 | 1,000 | 0.48 / 0.66 | 53.33 / 69.34 |
| date | 142,858 | 1 | 0.50 / 0.64 | 0.42 / 0.56 |
| date | 142,858 | 1,000 | 0.50 / 0.66 | 63.87 / 66.07 |
| date + maxPrice | 41,799 | 1 | 0.43 / 0.71 | 0.42 / 0.56 |
| date + maxPrice | 41,799 | 1,000 | 0.47 / 0.57 | 39.92 / 41.60 |
| carrier | 42,082 | 1 | 0.62 / 0.84 | 0.58 / 0.74 |
| carrier | 42,082 | 1,000 | 0.62 / 0.77 | 151.56 / 157.89 |
| origin | 29,375 | 1 | 0.81 / 1.61 | 0.74 / 2.15 |
| origin | 29,375 | 1,000 | 0.77 / 1.88 | 82.21 / 99.93 |
| origin + carrier + maxPrice | 365 | 1 | 51.35 / 67.14 | 47.74 / 63.42 |
| origin + carrier + maxPrice | 365 | 19 | 0.54 / 1.37 | 5.55 / 6.58 |
| route | 993 | 1 | 0.57 / 1.25 | 0.38 / 0.42 |
| route | 993 | 50 | 0.42 / 0.55 | 1.49 / 3.00 |
| route + date | 127 | 1 | 0.51 / 1.22 | 0.49 / 1.34 |
| route + date | 127 | 7 | 0.30 / 0.37 | 2.74 / 4.22 |
| route + carrier | 35 | 1 | 0.51 / 0.64 | 0.49 / 0.66 |
| route + carrier | 35 | 2 | 0.46 / 0.62 | 1.03 / 1.31 |
| route, maxPrice above all (worst realistic) | 993 | 1 | 0.45 / 0.54 | 0.39 / 0.44 |
| route, maxPrice above all (worst realistic) | 993 | 50 | 0.37 / 0.51 | 1.51 / 1.67 |

## API latency, uncached vs cached (ms, median / p95 of 30 runs, client side)

| Request | Uncached | Cached |
|---|---:|---:|
| `/api/v1/itineraries?limit=20` | 6.14 / 8.29 | 4.02 / 5.54 |
| `/api/v1/itineraries?limit=20&maxPrice=200` | 5.11 / 8.17 | 3.70 / 5.29 |
| `/api/v1/itineraries?limit=20&date=2026-11-08` | 4.23 / 4.73 | 2.89 / 3.14 |
| `/api/v1/itineraries?limit=20&date=2026-11-08&maxPrice=200` | 4.72 / 5.35 | 2.64 / 3.62 |
| `/api/v1/itineraries?limit=20&carrier=QR` | 4.91 / 6.23 | 2.64 / 3.61 |
| `/api/v1/itineraries?limit=20&origin=FRA` | 3.87 / 5.58 | 2.33 / 3.58 |
| `/api/v1/itineraries?limit=20&origin=FRA&carrier=QR&maxPrice=200` | 58.07 / 70.57 | 2.53 / 4.36 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB` | 3.30 / 3.84 | 2.34 / 2.57 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&date=2026-11-08` | 2.97 / 3.57 | 2.16 / 2.64 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&carrier=QR` | 3.27 / 3.76 | 2.14 / 2.40 |
| `/api/v1/itineraries?limit=20&origin=FRA&destination=JNB&maxPrice=999999` | 3.04 / 5.11 | 2.05 / 3.05 |
| `/api/v1/catalog` | 447.59 / 532.98 | 2.07 / 2.61 |

