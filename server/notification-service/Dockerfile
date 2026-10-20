# syntax=docker/dockerfile:1.7
# notification-service 镜像：多阶段构建（Maven 依赖缓存 + JRE 运行层）
# 构建上下文是 server/（见 deploy/docker-compose.yml），不是本目录。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY meeting-common/pom.xml meeting-common/
COPY gateway/pom.xml gateway/
COPY auth-service/pom.xml auth-service/
COPY meeting-service/pom.xml meeting-service/
COPY notification-service/pom.xml notification-service/

COPY meeting-common/src meeting-common/src
COPY notification-service/src notification-service/src
COPY .maven/settings.xml .maven/settings.xml

# 防御：即使 .dockerignore 被改坏，本地密钥配置也不许进镜像
RUN if find . -type f \( -name 'application-local.yml' -o -name 'application-local.yaml' \
        -o -name '*.local.yml' -o -name '*.local.yaml' \) | grep -q .; then \
        echo 'ERROR: local secret config found in build context' >&2; exit 1; \
    fi

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s .maven/settings.xml -pl notification-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R app:app /app

COPY --from=build /workspace/notification-service/target/*.jar /app/app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
