package com.rickyboy.appsweep;

// Runs inside Shizuku with shell (ADB) rights.
interface IShellService {
    // Reserved transaction code Shizuku uses to shut the service down.
    void destroy() = 16777114;

    // Runs a shell command and returns everything it printed.
    String exec(String command) = 1;
}
