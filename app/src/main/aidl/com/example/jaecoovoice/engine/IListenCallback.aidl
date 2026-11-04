// Contract with JaecooApp: docs/jaecoovoice-contract.md. Keep byte-identical in both repos.
package com.example.jaecoovoice.engine;

oneway interface IListenCallback {
    void onState(int state);
    void onLevel(float rms);
    void onResult(String text);
    void onError(int code, String detail);
}
