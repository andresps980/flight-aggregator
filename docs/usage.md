# Usage

> Only the template's example `greeting` endpoint exists so far. The flight search and ingestion APIs will be
> documented here as they are implemented.

## Run

```bash
./mvnw -pl flight-aggregator spring-boot:run
```

The `flight-aggregator` service starts on port `8080` (override with `--server.port=9090` or `SERVER_PORT`).

## API

### `GET /api/v1/greetings`

Example feature (spec: [`specs/000-example-greeting`](../specs/000-example-greeting/spec.md)).

| Query param | Required | Default | Rules |
|---|---|---|---|
| `name` | no | `World` | not blank, max 50 characters, surrounding spaces are trimmed |

```bash
curl "http://localhost:8080/api/v1/greetings?name=Ana"
```

```json
{ "message": "Hello, Ana!" }
```

Invalid input returns `400` with an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details body:

```bash
curl -i "http://localhost:8080/api/v1/greetings?name=%20"
```

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "Name must not be blank",
  "instance": "/api/v1/greetings"
}
```

### Operations

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Health (also `/actuator/health/liveness` and `/readiness`) |
| `GET /actuator/info` | Build/app info |

## Configuration

| Property | Default | Description |
|---|---|---|
| `app.greeting.template` | `Hello, %s!` | Greeting template; must contain exactly one `%s`. Validated at startup. |

Any property can be overridden with an environment variable (e.g. `APP_GREETING_TEMPLATE="Hola, %s!"`).
