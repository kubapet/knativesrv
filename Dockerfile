FROM adoptopenjdk/openjdk11:alpine as build-env
RUN apk add --no-cache build-base ncurses5-libs
RUN ln -s -f /usr/lib/libncurses.so.5.9 /usr/lib/libtinfo.so.5
WORKDIR /app
COPY . .
RUN /app/gradlew linkDebugExecutableLinuxX64


FROM alpine
WORKDIR /app
COPY --from=build-env /app/build/bin/linuxX64/debugExecutable/nativeweb.kexe /app/server.kexe
COPY --from=build-env /app/profile.png /app/profile.png
CMD ["/app/nativeweb.kexe", "8080"]