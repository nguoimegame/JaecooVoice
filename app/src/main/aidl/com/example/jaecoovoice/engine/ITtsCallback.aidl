// Contract with JaecooApp: docs/jaecoovoice-contract.md. Keep byte-identical in both repos.
package com.example.jaecoovoice.engine;

oneway interface ITtsCallback {
    void onStart();
    void onDone();
    void onError(String message);
}
