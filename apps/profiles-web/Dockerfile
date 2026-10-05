FROM debian:bookworm-slim

RUN mkdir /app
WORKDIR /app
COPY build/native/nativeCompile/profiles-web /app/profiles-web
COPY build/generated/bml/js-prod/ /app/client/
RUN chmod +x ./profiles-web

EXPOSE 9095
ENTRYPOINT ["./profiles-web", "-Dbml.clientDir=/app/client"]
