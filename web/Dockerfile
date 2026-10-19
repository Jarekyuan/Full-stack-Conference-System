# syntax=docker/dockerfile:1.7
# 前端镜像：Node 构建 -> Nginx 托管
# 构建上下文是仓库根目录（需要同时拿到 web/ 与 deploy/nginx.conf）
FROM node:20-alpine AS build
WORKDIR /app

# 依赖层单独缓存：改业务代码不会重新 npm ci
COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
