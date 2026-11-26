package com.orangecode.tianmu.websocket;

import com.orangecode.tianmu.common.DanmakuMetrics;

import com.fasterxml.jackson.databind.*;
import com.orangecode.tianmu.application.SessionAuthentication;
import com.orangecode.tianmu.application.VideoLookup;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.constants.WebSocketConstant;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.model.vo.bullet.*;
import com.orangecode.tianmu.producer.RocketMQProducer;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@Slf4j
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final RocketMQProducer producer;
    private final SessionAuthentication authentication;
    private final VideoLookup videos;
    private final RoomBatchBroadcaster broadcasts;
    private final DanmakuSendQueue sends;
    private final Executor preparation;
    // Only the channel's coordinator changes this flag.
    private boolean preparing;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final AttributeKey<Long> ROOM = AttributeKey.valueOf(WebSocketHandler.class, "videoId");
    private static final Runnable NO_OUTCOME = () -> { };

    WebSocketHandler(RocketMQProducer producer, SessionAuthentication authentication, VideoLookup videos,
                     RoomBatchBroadcaster broadcasts, DanmakuSendQueue.Limits limits,
                     Executor preparation) {
        this.producer = producer;
        this.authentication = authentication;
        this.videos = videos;
        this.broadcasts = broadcasts;
        this.sends = new DanmakuSendQueue(Objects.requireNonNull(limits));
        this.preparation = preparation;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        long started = System.nanoTime();
        long received = frame instanceof TimedDanmakuFrame timed ? timed.queuedAt : started;
        Long room = ctx.channel().attr(ROOM).get();
        if (room == null) { error(ctx, "无效的视频房间"); return; }
        if (frame.content().readableBytes() > 16384) { error(ctx, "弹幕消息过大"); return; }

        var input = new DanmakuSendQueue.Frame(room, frame.text(), received, started);
        switch (sends.offer(input)) {
            case START -> drain(ctx);
            case REJECTED -> {
                DanmakuMetrics.mqAdmissionRejected.increment();
                error(ctx, "弹幕发送过快，请稍后重试");
            }
            case QUEUED -> { }
        }
    }

    private void drain(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            sends.close();
            return;
        }
        if (preparing) return;
        var next = sends.take();
        if (next != null) {
            preparing = true;
            submitPreparation(ctx, next);
        }
    }

    private void submitPreparation(ChannelHandlerContext ctx, DanmakuSendQueue.Send slot) {
        var frame = slot.frame();
        long submitted = System.nanoTime();
        try {
            preparation.execute(() -> {
                long queued = frame.started() - frame.received() + System.nanoTime() - submitted;
                DanmakuMetrics.observe(DanmakuMetrics.Stage.WS_QUEUE, queued);
                var timedFrame = new DanmakuSendQueue.Frame(frame.room(), frame.json(),
                        frame.received(), frame.received() + queued);
                boolean completionPending = false;
                try {
                    if (ctx.channel().isActive()) {
                        completionPending = processFrame(ctx, timedFrame, slot, submitted - frame.started());
                    }
                } finally {
                    finishSend(ctx, slot, completionPending ? null : () -> NO_OUTCOME, 0, true);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            DanmakuMetrics.rejected.increment();
            sends.complete(slot, () -> error(ctx, "弹幕发送过快，请稍后重试"));
            // This pool is bounded by the same process-wide admission budget.
            // Rejection here normally means shutdown. Do not recurse through the backlog.
            sends.close();
            ctx.close();
        }
    }

    private boolean processFrame(ChannelHandlerContext ctx, DanmakuSendQueue.Frame frame, DanmakuSendQueue.Send slot,
                                 long orderWait) {
        long prepareStarted = System.nanoTime();
        DanmakuMetrics.observe(DanmakuMetrics.Stage.WS_SEND_ORDER_WAIT, orderWait);
        try {
            return prepareSend(ctx, frame, slot, orderWait);
        } finally {
            DanmakuMetrics.observe(DanmakuMetrics.Stage.WS_HANDLER, System.nanoTime() - prepareStarted);
        }
    }

    private boolean prepareSend(ChannelHandlerContext ctx, DanmakuSendQueue.Frame frame,
                                DanmakuSendQueue.Send slot, long orderWait) {
        long room = frame.room();
        JsonNode input;
        long parseStarted = System.nanoTime();
        try {
            input = JSON.readTree(frame.json());
        } catch (Exception malformed) {
            input = null;
        } finally {
            DanmakuMetrics.observe(DanmakuMetrics.Stage.JSON_PARSE, System.nanoTime() - parseStarted);
        }
        if (input == null || !input.isObject())
            return rejectPrepared(ctx, slot, () -> error(ctx, "弹幕格式错误"));
        try {
            // Browsers cannot set WebSocket Authorization headers. Credentials belong only
            // in the private send frame, never in URLs, broadcasts or persisted events.
            JsonNode credential = input.path("authorization");
            Long userId; long authNanos; long authStart = System.nanoTime();
            try { userId=authentication.authenticate(credential.isTextual() ? credential.textValue() : null); }
            finally {
                authNanos = System.nanoTime() - authStart;
                DanmakuMetrics.observe(DanmakuMetrics.Stage.AUTH, authNanos);
            }
            if (userId == null) return rejectPrepared(ctx, slot,
                    () -> reply(ctx, WebSocketConstant.LOGIN_MESSAGE, "登录已失效，请重新登录"));
            if (!matches(input.get("userId"), userId) || !matches(input.get("videoId"), room)) {
                return rejectPrepared(ctx, slot, () -> error(ctx, "弹幕身份或视频房间不一致"));
            }
            JsonNode content = input.path("content"), position = input.path("playbackTime"), color = input.get("color");
            if (!content.isTextual() || content.textValue().isBlank() || content.textValue().length() > 255
                    || !position.isNumber() || !Double.isFinite(position.doubleValue()) || position.doubleValue() < 0
                    || (color != null && (!color.isTextual() || !color.textValue().matches("#?(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})")))) {
                return rejectPrepared(ctx, slot, () -> error(ctx, "请检查弹幕内容、播放时间和颜色"));
            }
            long videoNanos; long videoStart = System.nanoTime();
            try { videos.requirePublished(room); }
            finally {
                videoNanos = System.nanoTime() - videoStart;
                DanmakuMetrics.observe(DanmakuMetrics.Stage.VIDEO_CHECK, videoNanos);
            }
            // Explicit projection prevents credentials and client timestamps leaking to MQ.
            var event = new SendBulletRequest();
            event.setUserId(userId); event.setVideoId(room); event.setContent(content.textValue());
            event.setPlaybackTime(position.doubleValue()); event.setColor(color == null ? "#ffffff" : color.textValue());
            event.setBulletId(IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID).nextId());
            event.setCreateTime(new Date());
            send(ctx, frame, slot, event, orderWait, authNanos, videoNanos);
            return true;
        } catch (BusinessException unavailableVideo) {
            return rejectPrepared(ctx, slot, () -> error(ctx, "视频不存在或不可发送弹幕"));
        } catch (Exception unavailable) {
            return rejectPrepared(ctx, slot, () -> failSend(ctx, unavailable));
        }
    }

    private boolean rejectPrepared(ChannelHandlerContext ctx, DanmakuSendQueue.Send slot, Runnable response) {
        finishSend(ctx, slot, () -> response, 0, false);
        return true;
    }

    private void send(ChannelHandlerContext ctx, DanmakuSendQueue.Frame frame, DanmakuSendQueue.Send slot,
                      SendBulletRequest event,
                      long orderWait, long authNanos, long videoNanos) {
        String payload = JSONUtil.toJsonStr(event);
        long started = System.nanoTime();
        String worker = Thread.currentThread().getName();
        String workerId = DanmakuMetrics.workerId(Thread.currentThread());

        var confirmation = producer.sendMessageAsync("tianmu-topic", payload);
        DanmakuMetrics.observe(DanmakuMetrics.Stage.MQ_SUBMIT, System.nanoTime() - started);
        confirmation.whenComplete((ignored, failure) -> {
            long done = System.nanoTime();
            DanmakuMetrics.observe(DanmakuMetrics.Stage.MQ_SEND, done - started);
            var timing = new RoomBatchBroadcaster.SendTiming(event.getBulletId(), worker, workerId,
                    frame.received(), frame.started(), orderWait, authNanos, videoNanos, started, done);
            finishSend(ctx, slot, () -> sendOutcome(ctx, event, timing, failure), done, false);
        });
    }

    private void finishSend(ChannelHandlerContext ctx, DanmakuSendQueue.Send slot,
                            Supplier<Runnable> outcome, long brokerDone, boolean preparationDone) {
        try {
            ctx.executor().execute(() -> {
                if (preparationDone) preparing = false;
                if (brokerDone != 0)
                    DanmakuMetrics.observe(DanmakuMetrics.Stage.MQ_CALLBACK_QUEUE, System.nanoTime() - brokerDone);
                if (outcome != null) sends.complete(slot, outcome.get());
                drain(ctx);
            });
        } catch (RejectedExecutionException full) {
            DanmakuMetrics.mqCallbackRejected.increment();
            sends.close();
            try {
                if (outcome != null) sends.complete(slot, outcome.get());
            } finally {
                ctx.close();
            }
        }
    }

    private Runnable sendOutcome(ChannelHandlerContext ctx, SendBulletRequest event,
                                 RoomBatchBroadcaster.SendTiming timing, Throwable failure) {
        long ready = System.nanoTime();
        return () -> {
            DanmakuMetrics.observe(DanmakuMetrics.Stage.MQ_REORDER_WAIT, System.nanoTime() - ready);
            try {
                if (failure == null) broadcast(event, timing);
                else {
                    DanmakuMetrics.mqFailed.increment();
                    failSend(ctx, failure);
                }
            } catch (RuntimeException unavailable) {
                failSend(ctx, unavailable);
            }
        };
    }

    private void broadcast(SendBulletRequest event, RoomBatchBroadcaster.SendTiming timing) {
        var output = new OnlineBulletResponse();
        output.setUserId(event.getUserId().toString());
        output.setBulletId(event.getBulletId().toString());
        output.setPlaybackTime(event.getPlaybackTime());
        output.setText(event.getContent());
        broadcasts.append(event.getVideoId(), output, timing);
    }

    private void failSend(ChannelHandlerContext ctx, Throwable failure) {
        log.warn("Danmaku send failed, cause={}", failure.getClass().getSimpleName());
        if (ctx.channel().isActive()) error(ctx, "弹幕服务暂不可用，请稍后重试");
    }

    private boolean matches(JsonNode supplied, long trusted) {
        return supplied == null || ((supplied.isTextual() || supplied.isIntegralNumber())
                && Long.toString(trusted).equals(supplied.asText()));
    }
    private static String message(int type, Object data) {
        var response = new BulletScreenResponse(); response.setType(type); response.setData(data);
        return JSONUtil.toJsonStr(response);
    }
    private void error(ChannelHandlerContext ctx, String text) { reply(ctx, WebSocketConstant.ERROR_MESSAGE, text); }
    private void reply(ChannelHandlerContext ctx, int type, Object data) {
        // An inline reply must not overtake a broadcast already queued on the socket loop.
        try {
            ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(new TextWebSocketFrame(message(type, data))));
        } catch (RejectedExecutionException stopped) {
            // The socket loop is shutting down.
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            try {
                String path = handshake.requestUri();
                if (!path.matches("/ws/bulletScreen/[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
                long room = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                ctx.channel().attr(ROOM).set(room);
                // Guests may receive public danmaku; each send revalidates a current token.
                broadcasts.join(room, ctx.channel());
            } catch (IllegalArgumentException invalidRoom) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1008, "Invalid video room")).addListener(ChannelFutureListener.CLOSE);
            }
        } else if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            ctx.writeAndFlush(new PingWebSocketFrame());
        } else super.userEventTriggered(ctx, event);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        sends.close();
        Long room = ctx.channel().attr(ROOM).getAndSet(null);
        if (room == null) return;
        broadcasts.leave(room, ctx.channel());
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Danmaku connection failed, cause={}", cause.getClass().getSimpleName());
        ctx.close();
    }
}
