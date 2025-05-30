package com.aoneconsultancy.zeromqpoc.service;

import com.aoneconsultancy.zeromqpoc.config.ZmqProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.DisposableBean;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service that manages ZeroMQ push/pull sockets.
 */
public class ZmqService implements DisposableBean {

    private final ZContext context = new ZContext();
    private final ZMQ.Socket pushSocket;
    private final ZMQ.Socket pullSocket;
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private final List<Consumer<byte[]>> listeners = new CopyOnWriteArrayList<>();

    public ZmqService(ZmqProperties properties) {

        pushSocket = context.createSocket(SocketType.PUSH);
        pushSocket.setHWM(properties.getBufferSize());
        pushSocket.bind(properties.getPushBindAddress());

        pullSocket = context.createSocket(SocketType.PULL);
        pullSocket.setHWM(properties.getBufferSize());
        pullSocket.connect(properties.getPullConnectAddress());
    }

    @PostConstruct
    public void startListener() {
        listenerExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] data = pullSocket.recv(0);
                if (data != null) {
                    String json = new String(data);
                    System.out.println("Received: " + json);
                    boolean received = receivedMessages.offer(json);
                    for (Consumer<byte[]> listener : listeners) {
                        listener.accept(data);
                    }
                }
            }
        });
    }

    public void sendBytes(byte[] data) {
        pushSocket.send(data, 0);
    }

    public void send(Object payload) throws JsonProcessingException {
        sendBytes(mapper.writeValueAsBytes(payload));
    }

    public void registerListener(Consumer<byte[]> listener) {
        listeners.add(listener);
    }

    /**
     * Remove a previously registered listener.
     * @param listener the listener to remove
     */
    public void unregisterListener(Consumer<byte[]> listener) {
        listeners.remove(listener);
    }

    public String pollReceived(long timeout, TimeUnit unit) throws InterruptedException {
        return receivedMessages.poll(timeout, unit);
    }

    @Override
    public void destroy() {
        listenerExecutor.shutdownNow();
        pushSocket.close();
        pullSocket.close();
        context.close();
    }
}
