// NAV-021 R2 (ADR-0017 §2): the small interface of OnDeviceRoutingService in the :routing process. The request (a few
// KB) travels in the binder call; the OSRM answer is written as one RoutingWire frame into the pipe's write end [sink]
// and never into a binder reply (AC 25). The service closes [sink] when the frame is written.
package mn.navmn.app.routing.ipc;

interface IOnDeviceRouting {
    /** Routes [requestJson] (the exact ADR-0009 §2 body) on the engine for routing file [version] at [tarPath]. */
    oneway void route(String tarPath, String version, String requestJson, in ParcelFileDescriptor sink);

    /** NAV-022 install self-test: the same as route, on a fresh engine that is closed afterwards. */
    oneway void selfTest(String tarPath, String version, String requestJson, in ParcelFileDescriptor sink);

    /** The routing process's pid (crash tests, timeout recovery, the benchmark's PSS reading). */
    int pid();

    /** AC 4 test hook: main-process initialisations run in this process (must be 0). */
    int mainInitCount();

    /** Debug.getPss() of this process in KiB (AC 34 benchmark). */
    long pssKb();
}
