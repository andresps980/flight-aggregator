#!/bin/sh
# Writes the runtime configuration of the frontend container, then starts nginx (spec 006 AC-22, ADR-0024).
#   DEMO_CONTROLS   true/false (default false): show the demo panel and proxy /gds-admin/providers… to gds-mock
#   AGGREGATOR_URL  default http://flight-aggregator:8080
#   GDS_MOCK_URL    default http://gds-mock:8081
# One image serves every environment: nothing here is decided at build time.
set -eu

out=/tmp/nginx
mkdir -p "$out"

aggregator_url="${AGGREGATOR_URL:-http://flight-aggregator:8080}"
gds_mock_url="${GDS_MOCK_URL:-http://gds-mock:8081}"
# The URLs are written into the nginx config: accept only scheme://host[:port], no path (with a variable proxy_pass a
# path would replace every request URI) and nothing that could inject nginx directives.
for url in "$aggregator_url" "$gds_mock_url"; do
  if ! echo "$url" | grep -Eq '^https?://[A-Za-z0-9.-]+(:[0-9]+)?$'; then
    echo "frontend: invalid upstream URL '$url' (expected http(s)://host[:port], no path)" >&2
    exit 1
  fi
done

case "$(echo "${DEMO_CONTROLS:-false}" | tr '[:upper:]' '[:lower:]')" in
  true | 1 | yes | on) demo=true ;;
  *) demo=false ;;
esac

# The DNS server the container was given (127.0.0.11 inside a compose network), so upstream names are resolved at
# request time and re-resolved after a container restart.
resolver="$(awk '/^nameserver/ { print $2; exit }' /etc/resolv.conf)"
case "$resolver" in *:*) resolver="[$resolver]" ;; esac

{
  echo "resolver ${resolver:-127.0.0.11} valid=10s ipv6=off;"
  echo "resolver_timeout 3s;"
  echo "set \$aggregator_url $aggregator_url;"
  echo "set \$gds_mock_url $gds_mock_url;"
  if [ "$demo" = true ]; then
    cat <<'CONF'
# Demo controls on: only gds-mock's provider admin paths, rewritten from /gds-admin/... to /admin/...
location ~ ^/gds-admin/providers(/[A-Za-z0-9-]+/outage)?$ {
  rewrite ^/gds-admin/(.*)$ /admin/$1 break;
  proxy_pass $gds_mock_url;
  proxy_read_timeout 10s;
}
location /gds-admin/ {
  return 404;
}
CONF
  else
    echo "location /gds-admin/ { return 404; }"
  fi
} > "$out/runtime.conf"

printf '{ "demoControls": %s }\n' "$demo" > "$out/config.json"
echo "frontend: demo controls $demo, aggregator $aggregator_url"

exec "$@"
