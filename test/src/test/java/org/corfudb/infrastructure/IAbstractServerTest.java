package org.corfudb.infrastructure;

import lombok.Getter;
import org.assertj.core.api.Assertions;
import org.corfudb.AbstractCorfuTest;
import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.protocols.wireprotocol.CorfuPayloadMsg;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.clients.BaseClient;
import org.corfudb.runtime.clients.IClientRouter;
import org.corfudb.runtime.clients.LayoutClient;
import org.corfudb.runtime.clients.LogUnitClient;
import org.corfudb.runtime.clients.ManagementClient;
import org.corfudb.runtime.clients.SequencerClient;
import org.corfudb.runtime.clients.TestClientRouter;
import org.junit.Before;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by mwei on 12/12/15.
 */
public interface IAbstractServerTest {

    public static final UUID testClientId = UUID.nameUUIDFromBytes("TEST_CLIENT".getBytes());

    TestServerRouter getRouter();
    AtomicInteger getRequestCounter();


    default void setServer(AbstractServer server) {
        TestServerRouter router = getRouter();
        router.reset();
        router.addServer(server);
    }

    AbstractServer getDefaultServer();

    @Before
    default void resetTest() {
        TestServerRouter router = getRouter();
        router.reset();
        router.addServer(getDefaultServer());
        getRequestCounter().set(0);
    }

    default List<CorfuMsg> getResponseMessages() {
        return getRouter().getResponseMessages();
    }

    default CorfuMsg getLastMessage() {
        TestServerRouter router = getRouter();
        if (router.getResponseMessages().size() == 0) return null;
        return router.getResponseMessages().get(router.getResponseMessages().size() - 1);
    }

    @SuppressWarnings("unchecked")
    default <T extends CorfuMsg> T getLastMessageAs(Class<T> type) {
        return (T) getLastMessage();
    }

    @SuppressWarnings("unchecked")
    default <T> T getLastPayloadMessageAs(Class<T> type) {
        Assertions.assertThat(getLastMessage())
                .isInstanceOf(CorfuPayloadMsg.class);
        return ((CorfuPayloadMsg<T>)getLastMessage()).getPayload();
    }
    default void sendMessage(CorfuMsg message) {
        sendMessage(testClientId, message);
    }

    default void sendMessage(UUID clientId, CorfuMsg message) {
        TestServerRouter router = getRouter();
        message.setClientID(clientId);
        message.setRequestID(getRequestCounter().getAndIncrement());
        router.sendServerMessage(message);
    }

    /**
     * A map of maps to endpoint->routers, mapped for each runtime instance captured
     */
    final Map<CorfuRuntime, Map<String, TestClientRouter>>
            runtimeRouterMap = new ConcurrentHashMap<>();

    /**
     * Function for obtaining a router, given a runtime and an endpoint.
     *
     * @param runtime  The CorfuRuntime to obtain a router for.
     * @param endpoint An endpoint string for the router.
     * @return
     */
    default IClientRouter getRouterFunction(CorfuRuntime runtime, String endpoint) {
        TestServerRouter router = getRouter();
        runtimeRouterMap.putIfAbsent(runtime, new ConcurrentHashMap<>());
        if (!endpoint.startsWith("test:")) {
            throw new RuntimeException("Unsupported endpoint in test: " + endpoint);
        }
        return runtimeRouterMap.get(runtime).computeIfAbsent(endpoint,
                x -> {
                    TestClientRouter tcn =
                            new TestClientRouter(router);
                    tcn.addClient(new BaseClient())
                            .addClient(new SequencerClient())
                            .addClient(new LayoutClient())
                            .addClient(new LogUnitClient())
                            .addClient(new ManagementClient());
                    return tcn;
                }
        );
    }
}
