# Assessment statement

Verbatim copy of the technical assessment this repository solves.

---

1. Business Context & Objective
As an Online Travel Agency (OTA), our core value proposition is finding the best flights across multiple global distribution networks. You are tasked with building a Flight Aggregation & Cache Optimization System. The application must ingest, process, deduplicate, and display flight itineraries sourced from three distinct external Global Distribution Systems (GDS Providers: Mock-Alpha, Mock-Beta, and Mock-Gamma).
2. Core Functional Requirements
    • Multi-Provider Integration: Implement an ingestion layer that queries all 3 mock APIs simultaneously. Each provider returns data in different legacy structures (e.g., Alpha uses raw JSON lists, Beta uses deeply nested XML structures, Gamma uses comma-separated string streams).
    • The Aggregation & Deduplication Logic: Identify identical itineraries (same airline, flight numbers, connections, and departure times) offered by different providers. Your core domain must select the cheapest option, apply a default 5% OTA operational markup, and filter out dead legs or invalid connections.
    • High-Volume Optimization: The system must seamlessly handle an inventory profile ranging from 100,000 to 1,000,000 flight combinations daily.
    • The Search API & Frontend: Implement a clean search API supporting high-performance filtering (by Origin, Destination, Max Price, and Carrier) using an optimized pagination strategy (justify your choice of Keyset vs. Offset in your documentation). Build a frontend dashboard to search, filter, and visualize these itineraries in real-time. 
3. Technical Constraints & Architecture
    • Clean Architecture / DDD: Isolate your core domain business rules entirely from third-party frameworks, network clients, and database components.
    • Resiliency: The mock external provider APIs have artificial latencies (ranging from 100ms to 5000ms) and occasional 500 errors. Your aggregation logic must not block or fail entirely if one provider drops out.
    • Dockerization: The complete stack (Backend, Frontend, Database/Cache layer) must boot flawlessly using a single command: docker compose up --build. 
4. Delivery Expectations
Provide a Git repository by Sunday 8th EOD (the team needs to review it before the session) containing the complete source code, a comprehensive test suite (unit and integration tests), and a SOLUTION.md detailing:
    1. Architectural decisions and AI tools utilized (prompts or agent systems engineered to write the code).
    2. Justification for data structure modeling and pagination handling up to 1,000,000 rows.
    3. System limitations and trade-offs made.
