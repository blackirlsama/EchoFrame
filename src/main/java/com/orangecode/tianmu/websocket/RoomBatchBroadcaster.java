package com.orangecode.tianmu.websocket;

import com.orangecode.tianmu.common.DanmakuMetrics;

import cn.hutool.json.JSONUtil;
import com.orangecode.tianmu.constants.WebSocketConstant;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded pending batch and at most one timer per active room. */
@Component
@Slf4j
public class RoomBatchBroadcaster implements AutoCloseable {
    private static final String PREFIX = "{\"type\":" + WebSocketConstant.ONLINE_BULLET_BATCH + ",\"data\":[";
    private static final int ENVELOPE_BYTES = PREFIX.getBytes(StandardCharsets.UTF_8).length + 2;
    private final ConcurrentMap<Long, Room> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Limits limits;
    private final AtomicBoolean closed = new AtomicBoolean();

    record Limits(long windowMs, int maxItems, int maxBytes) {
        Limits {
            // Keep the batching window below the existing one-second send confirmation wait.
            if (windowMs < 1 || windowMs > 500 || maxItems < 1 || maxItems > 1000 || maxBytes < 4096 || maxBytes > 65536)
                throw new IllegalArgumentException("Invalid danmaku batch limits");
        }
    }

    @Autowired
    public RoomBatchBroadcaster(@Value("${netty.danmaku-batch.window-ms:100}") long windowMs,
                                @Value("${netty.danmaku-batch.max-items:50}") int maxItems,
                                @Value("${netty.danmaku-batch.max-bytes:16384}") int maxBytes) {
        this(new Limits(windowMs, maxItems, maxBytes), newScheduler());
    }

    RoomBatchBroadcaster(Limits limits, ScheduledExecutorService scheduler) {
        this.limits = limits; this.scheduler = scheduler;
    }

    private static ScheduledExecutorService newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            var thread = new Thread(runnable, "danmaku-room-flush"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    record SendTiming(long bulletId, String worker, String workerId, long received, long started, long orderWaitNanos, long authNanos,
                      long videoNanos, long mqStarted, long mqDone) {}
    private record BroadcastTiming(SendTiming send, long enqueued) {}
    private record Pending(String json, BroadcastTiming timing) {}

    private static final class Room {
        final long id;
        Room(long id) { this.id = id; }
        final Map<Channel, Shard> members = new HashMap<>();
        final Map<EventLoop, Shard> shards = new LinkedHashMap<>();
        final List<Pending> pending = new ArrayList<>();
        int bytes = ENVELOPE_BYTES;
        long generation;
        ScheduledFuture<?> timer;
        ScheduledFuture<?> onlineTimer;
        boolean disposed;
    }

    private static final class Shard {
        final EventLoop loop;
        // Only this EventLoop reads or mutates the recipient set.
        final Set<Channel> channels = new LinkedHashSet<>();
        // The room lock protects the count used at scheduling time.
        int viewers;

        Shard(EventLoop loop) {
            this.loop = loop;
        }

        void write(byte[] payload, Broadcast batch) {
            var frame = new TextWebSocketFrame(Unpooled.wrappedBuffer(payload));
            var completion = batch == null ? null : new ShardWrite(batch);
            try {
                for (Channel channel : channels) {
                    if (completion == null) {
                        channel.writeAndFlush(frame.retainedDuplicate(), channel.voidPromise());
                    } else {
                        completion.write(channel, frame);
                    }
                }
            } finally {
                frame.release();
                if (completion != null) completion.finish();
            }
        }

        void closeChannels() {
            for (Channel channel : channels) channel.close();
            channels.clear();
        }
    }

    private static final class ShardWrite implements ChannelFutureListener {
        private final Broadcast batch;
        private int pending = 1, blocked, failed;

        ShardWrite(Broadcast batch) {
            this.batch = batch;
        }

        void write(Channel channel, TextWebSocketFrame frame) {
            if (!channel.isWritable()) blocked++;
            pending++;
            channel.writeAndFlush(frame.retainedDuplicate()).addListener(this);
        }

        @Override public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) failed++;
            finish();
        }

        void finish() {
            if (--pending == 0) batch.shardDone(blocked, failed);
        }
    }

    public void join(long roomId, Channel channel) {
        rooms.compute(roomId, (id, previous) -> {
            Room room = previous == null ? new Room(roomId) : previous;
            synchronized (room) {
                if (closed.get() || room.disposed) throw new IllegalStateException("Room broadcaster is closed");
                Shard existing = room.members.get(channel);
                Shard shard = existing == null ? room.shards.computeIfAbsent(channel.eventLoop(), Shard::new) : existing;
                int viewers = room.members.size() + (existing == null ? 1 : 0);
                if (existing == null) {
                    room.members.put(channel, shard);
                    shard.viewers++;
                }
                // Join, leave and batch tasks enter each EventLoop in room order.
                try {
                    shard.loop.execute(() -> {
                        if (existing == null) {
                            shard.channels.add(channel);
                            channel.closeFuture().addListener(ignored -> leave(roomId, channel));
                        }
                        channel.writeAndFlush(new TextWebSocketFrame(onlineMessage(viewers)), channel.voidPromise());
                    });
                } catch (RejectedExecutionException stopped) {
                    if (existing == null) removeMember(room, channel);
                    throw stopped;
                }
                if (viewers > 1) scheduleOnline(room);
            }
            return room;
        });
        // A new room can race the shutdown walk before compute publishes it.
        if (closed.get()) {
            leave(roomId, channel);
            channel.close();
        }
    }

    private static Shard removeMember(Room room, Channel channel) {
        Shard shard = room.members.remove(channel);
        if (shard != null && --shard.viewers == 0) room.shards.remove(shard.loop);
        return shard;
    }

    public void leave(long roomId, Channel channel) {
        rooms.computeIfPresent(roomId, (id, room) -> {
            synchronized (room) {
                if (room.disposed) return null;
                Shard shard = removeMember(room, channel);
                if (shard == null) return room;
                try {
                    shard.loop.execute(() -> shard.channels.remove(channel));
                } catch (RejectedExecutionException stopped) {
                    // The loop cannot send any more, and room membership is already updated.
                }
                if (room.members.isEmpty()) {
                    discard(room); cancelOnline(room); room.disposed = true; return null;
                }
                if (!closed.get()) scheduleOnline(room);
                return room;
            }
        });
    }

    public void append(long roomId, OnlineBulletResponse item) {
        append(roomId, item, null);
    }

    void append(long roomId, OnlineBulletResponse item, SendTiming timing) {
        Room room = rooms.get(roomId);
        if (room == null || closed.get()) {
            DanmakuMetrics.broadcastSkipped.increment();
            return;
        }
        String json = JSONUtil.toJsonStr(item);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + ENVELOPE_BYTES > limits.maxBytes()) throw new IllegalArgumentException("Danmaku exceeds batch byte limit");
        synchronized (room) {
            if (room.disposed || closed.get()) {
                DanmakuMetrics.broadcastSkipped.increment();
                return;
            }
            int separator = room.pending.isEmpty() ? 0 : 1;
            if (room.bytes + separator + bytes > limits.maxBytes()) flush(room);
            room.bytes += bytes + (room.pending.isEmpty() ? 0 : 1);
            long enqueued = System.nanoTime();
            if (timing != null) DanmakuMetrics.observe(DanmakuMetrics.Stage.MQ_TO_BATCH, enqueued - timing.mqDone());
            room.pending.add(new Pending(json, timing == null ? null : new BroadcastTiming(timing, enqueued)));
            if (room.pending.size() >= limits.maxItems() || room.bytes >= limits.maxBytes()) {
                flush(room);
            } else if (room.timer == null) {
                long generation = ++room.generation;
                long due = enqueued + TimeUnit.MILLISECONDS.toNanos(limits.windowMs());
                room.timer = scheduler.schedule(() -> {
                    long fired = System.nanoTime();
                    synchronized (room) {
                        if (!room.disposed && room.generation == generation) {
                            DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_TIMER_LAG, Math.max(0, fired - due));
                            DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_TIMER_LOCK, System.nanoTime() - fired);
                            flush(room);
                        }
                    }
                }, limits.windowMs(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Caller holds the room lock so timer/count/byte flushes preserve per-room append order. */
    private void flush(Room room) {
        if (room.pending.isEmpty()) return;
        long flushStarted = System.nanoTime();
        int items = room.pending.size();
        int bytes = room.bytes;
        var frame = new StringJoiner(",", PREFIX, "]}");
        var timings = new ArrayList<BroadcastTiming>(items);
        for (Pending pending : room.pending) {
            frame.add(pending.json());
            if (pending.timing() != null) {
                timings.add(pending.timing());
                DanmakuMetrics.observe(DanmakuMetrics.Stage.BATCH_WAIT, flushStarted - pending.timing().enqueued());
            }
        }
        byte[] payload = frame.toString().getBytes(StandardCharsets.UTF_8);
        discard(room);
        DanmakuMetrics.frames.increment();
        DanmakuMetrics.recipients.add(room.members.size());
        var batch = new Broadcast(room, timings, flushStarted, items, bytes);
        for (Shard shard : room.shards.values()) {
            try {
                shard.loop.execute(() -> shard.write(payload, batch));
            } catch (RejectedExecutionException stopped) {
                batch.shardDone(0, shard.viewers);
            }
        }
        batch.enqueued();
    }

    private static final class Broadcast {
        final long roomId, flushStarted, dispatchStarted = System.nanoTime();
        final int items, bytes, viewers;
        final List<BroadcastTiming> timings;
        final AtomicInteger remaining, blocked = new AtomicInteger(), failed = new AtomicInteger();
        long dispatchDone;

        Broadcast(Room room, List<BroadcastTiming> timings, long flushStarted, int items, int bytes) {
            this.roomId = room.id;
            this.flushStarted = flushStarted;
            this.timings = timings;
            this.items = items;
            this.bytes = bytes;
            this.viewers = room.members.size();
            this.remaining = new AtomicInteger(room.shards.size() + 1);
            DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_PREPARE, dispatchStarted - flushStarted);
        }

        void enqueued() {
            dispatchDone = System.nanoTime();
            DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_DISPATCH, dispatchDone - dispatchStarted);
            complete();
        }

        void shardDone(int nonWritable, int failures) {
            blocked.addAndGet(nonWritable);
            failed.addAndGet(failures);
            complete();
        }

        private void complete() {
            if (remaining.decrementAndGet() != 0) return;
            long done = System.nanoTime();
            int nonWritable = blocked.get(), failedWrites = failed.get();
            DanmakuMetrics.nonWritable.add(nonWritable);
            DanmakuMetrics.writeFailures.add(failedWrites);
            DanmakuMetrics.observe(DanmakuMetrics.Stage.BROADCAST_WRITE, done - dispatchStarted);
            for (BroadcastTiming timing : timings) {
                SendTiming send = timing.send();
                long elapsed = done - send.received();
                DanmakuMetrics.observe(DanmakuMetrics.Stage.WS_TO_WRITE_COMPLETE, elapsed);
                if (DanmakuMetrics.allowSlowLog(elapsed)) {
                    log.warn("danmaku_slow room_id={} bullet_id={} worker={} worker_id={} ws_queue_ms={} send_order_wait_ms={} auth_ms={} "
                                    + "video_ms={} mq_ms={} mq_to_batch_ms={} batch_wait_ms={} flush_prepare_ms={} "
                                    + "flush_dispatch_ms={} broadcast_write_ms={} total_ms={} items={} bytes={} "
                                    + "viewers={} non_writable={} failed_writes={}",
                            roomId, send.bulletId(), send.worker(), send.workerId(), ms(send.started() - send.received()),
                            ms(send.orderWaitNanos()), ms(send.authNanos()), ms(send.videoNanos()), ms(send.mqDone() - send.mqStarted()),
                            ms(timing.enqueued() - send.mqDone()), ms(flushStarted - timing.enqueued()),
                            ms(dispatchStarted - flushStarted), ms(dispatchDone - dispatchStarted),
                            ms(done - dispatchStarted), ms(elapsed), items, bytes, viewers, nonWritable, failedWrites);
                }
            }
        }
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private void discard(Room room) {
        ++room.generation;
        if (room.timer != null) room.timer.cancel(false);
        room.timer = null; room.pending.clear(); room.bytes = ENVELOPE_BYTES;
    }

    private static String onlineMessage(int viewers) {
        return "{\"type\":1,\"data\":" + viewers + "}";
    }

    private void online(Room room) {
        DanmakuMetrics.onlineFrames.increment();
        byte[] payload = onlineMessage(room.members.size()).getBytes(StandardCharsets.UTF_8);
        for (Shard shard : room.shards.values()) {
            try {
                shard.loop.execute(() -> shard.write(payload, null));
            } catch (RejectedExecutionException stopped) {
                // A stopped EventLoop has no live viewers for this update.
            }
        }
    }

    private void scheduleOnline(Room room) {
        if (room.onlineTimer != null) return;
        room.onlineTimer = scheduler.schedule(() -> {
            synchronized (room) {
                room.onlineTimer = null;
                if (!room.disposed && !closed.get()) online(room);
            }
        }, 1000, TimeUnit.MILLISECONDS);
    }
    private void cancelOnline(Room room) {
        if (room.onlineTimer != null) room.onlineTimer.cancel(false);
        room.onlineTimer = null;
    }

    int roomCount() { return rooms.size(); }

    @Override @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var closing = new ArrayList<Future<?>>();
        for (Room room : rooms.values()) {
            synchronized (room) {
                flush(room);
                discard(room);
                cancelOnline(room);
                room.disposed = true;
                for (Shard shard : room.shards.values()) {
                    try {
                        Future<?> close = shard.loop.submit(shard::closeChannels);
                        if (!shard.loop.inEventLoop()) closing.add(close);
                    } catch (RejectedExecutionException stopped) {
                        // The EventLoop already owns closing its remaining channels.
                    }
                }
            }
        }
        rooms.clear();
        scheduler.shutdownNow();
        closing.forEach(Future::awaitUninterruptibly);
    }
}
