# syntax=docker/dockerfile:1
# Dashboard image (spec 006 AC-24): Node builds the static files, nginx serves them. No Node.js in the final image.

# --- build: the Node LTS pinned in .nvmrc (ADR-0024)
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# --- runtime: nginx, unprivileged
FROM nginx:1.30-alpine
COPY docker/nginx.conf /etc/nginx/nginx.conf
COPY docker/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY docker/entrypoint.sh /usr/local/bin/frontend-entrypoint.sh
COPY --from=build /app/dist /usr/share/nginx/html
RUN chmod 0755 /usr/local/bin/frontend-entrypoint.sh && rm -f /etc/nginx/conf.d/default.conf
USER nginx
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/frontend-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
