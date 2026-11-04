// Contract with JaecooApp: docs/jaecoovoice-contract.md. Keep byte-identical in both repos.
package com.example.jaecoovoice.engine;

oneway interface IModelCallback {
    void onProgress(int percent);
    void onDone();
    void onError(String message);
}
