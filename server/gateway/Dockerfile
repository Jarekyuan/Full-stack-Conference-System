# syntax=docker/dockerfile:1.7
# gateway 镜像：多阶段构建（Maven 依赖缓存 + JRE 运行层）
# 构建上下文是 server/（见 deploy/docker-compose.yml），不是本目录。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先复制全部 POM：Maven 需要父 POM 的 <modules> 才认识 reactor。
# 依赖下载放进 BuildKit cache mount，源码变化不会重新下载依赖。
COPY pom.xml ./
COPY meeting-common/pom.xml meeting-common/
COPY gateway/pom.xml gateway/
COPY auth-service/pom.xml auth-service/
COPY meeting-service/pom.xml meeting-service/
COPY notification-service/pom.xml notification-service/

COPY meeting-common/src meeting-common/src
COPY gateway/src gateway/src
COPY .maven/settings.xml .maven/settings.xml

# 防御：即使 .dockerignore 被改坏，本地密钥配置也不许进镜像
RUN if find . -type f \( -name 'application-local.yml' -o -name 'application-local.yaml' \
        -o -name '*.local.yml' -o -name '*.local.yaml' \) | grep -q .; then \
        echo 'ERROR: local secret config found in build context' >&2; exit 1; \
    fi

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s .maven/settings.xml -pl gateway -am -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

# 非 root 运行；logs/ 留给 logback
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R app:app /app

COPY --from=build /workspace/gateway/target/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
