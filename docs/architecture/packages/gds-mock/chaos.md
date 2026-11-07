# gds-mock · chaos

`com.andresps.gdsmock.chaos.domain`, `….chaos.infrastructure.adapter.in.web`, `….chaos.infrastructure.config`

The statement's unreliable providers (spec 002): latency between 100 ms and 5000 ms, random `500`s (10 % by default)
and an outage switch per provider (`503`). `ChaosPolicy` decides, in plain Java with injected randomness;
`ChaosInterceptor` applies the decision before the feed controller runs — sleeping, then letting the request through
or throwing — so serializers and controllers know nothing about chaos. The admin API toggles outages at runtime; the
dashboard's demo controls reach it through nginx `/gds-admin/`.

## domain

```mermaid
classDiagram
    %% package: com.andresps.gdsmock.chaos.domain
    class ChaosPolicy {
        +decide(ProviderId provider) ChaosDecision
        +settings(ProviderId provider) ChaosSettings
        +providers() List~ProviderId~
    }
    class ChaosSettings {
        <<record>>
        +Duration latencyMin
        +Duration latencyMax
        +double errorRate
    }
    class OutageRegistry {
        +start(ProviderId provider)
        +end(ProviderId provider)
        +isDown(ProviderId provider) boolean
    }
    class ChaosDecision {
        <<sealed interface>>
        Proceed(Duration latency)
        Fail(Duration latency)
        Outage()
    }

    ChaosPolicy o-- "1..*" ChaosSettings : per provider
    ChaosPolicy --> OutageRegistry
    ChaosPolicy ..> ChaosDecision : returns
```

## web and config

```mermaid
classDiagram
    %% package: com.andresps.gdsmock.chaos.infrastructure.adapter.in.web
    %% package: com.andresps.gdsmock.chaos.infrastructure.config
    class ChaosInterceptor {
        on /*/flights, REQUEST dispatch only
        +preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) boolean
    }
    class Sleeper {
        <<interface>>
        +sleep(Duration duration)
    }
    class AdminProviderController {
        GET /admin/providers
        POST /admin/providers/:id/outage
        DELETE /admin/providers/:id/outage
        +providers() List~ProviderChaosResponse~
    }
    class ProviderChaosResponse {
        <<record>>
        +String id
        +long latencyMinMs
        +long latencyMaxMs
        +double errorRate
        +boolean outage
    }
    class ChaosExceptionHandler {
        InjectedProviderErrorException → 500
        ProviderOutageException → 503
    }
    class InjectedProviderErrorException
    class ProviderOutageException
    class ChaosConfig {
        composition root
    }
    class ChaosProperties {
        <<record>>
        +Values defaults
        +Map providers
    }

    ChaosInterceptor --> Sleeper
    ChaosInterceptor ..> InjectedProviderErrorException : throws
    ChaosInterceptor ..> ProviderOutageException : throws
    ChaosExceptionHandler ..> InjectedProviderErrorException
    ChaosExceptionHandler ..> ProviderOutageException
    AdminProviderController ..> ProviderChaosResponse
    ChaosConfig ..> ChaosProperties : binds gds.chaos.*
    ChaosConfig ..> ChaosInterceptor : registers
```

The interceptor ignores `ASYNC` dispatches: a streamed feed is dispatched again when its body completes, and applying
chaos twice doubled the latency and could fail a feed already sent (found by `ConcurrencyIT`).

**Interactions with other packages**

- `ChaosInterceptor` → `ChaosPolicy` (domain) and `FeedRequestResolver`
  ([inventory · infrastructure](inventory-infrastructure.md)); `AdminProviderController` → `ChaosPolicy`,
  `OutageRegistry`.
- `ChaosConfig` → `ProviderFormats` (inventory) for the provider ids; `Sleeper` is `Thread::sleep` in production and
  replaced in tests, so chaos is tested without sleeping.
- `ChaosExceptionHandler` builds its Problem Details with `GlobalExceptionHandler.problem`
  ([shared](shared.md)); → `ProviderId` and `UnknownProviderException` from `gdsmock.shared.domain`.
- ← the aggregator sees only the effect: slow answers, `500`s and `503`s, which its retries and circuit breakers
  absorb.
