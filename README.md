# How to reproduce the bug

- Clone, build and run this demo webserver application

```
git clone https://github.com/kubapet/knativesrv/tree/ktorbug . 
./gradlew linkDebugExecutableLinuxX64
cp ./build/bin/linuxX64/debugExecutable/nativeweb.kexe ./server.kexe
./server.kexe 8080
``` 

- Then open the browser and go to `http://localhost:8080`. 
  If you see the welcome message, the server is running
  (you can even check the beautiful tux at `http://localhost:8080/tux.png`).
  So far so good.
- Then go to the `http://localhost:8080/test` endpoind which is utilizing the Ktor
  client to fetch the main page at google.com. If the app doesn't crash immediately,
  try to refresh it ~5-10 times.
- You will get:
```
/mnt/agent/work/f01984a9f5203417/runtime/src/main/cpp/Memory.cpp:1271: runtime assert: Must be positive
```

- Running the app via Valgrind gives some additional details when it crashes:
```
valgrind --leak-check=full ./server.kexe 3200
```