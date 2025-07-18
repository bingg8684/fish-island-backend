package com.cong.fishisland.websocket.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cong.fishisland.common.ErrorCode;
import com.cong.fishisland.config.ThreadPoolConfig;
import com.cong.fishisland.constant.UserConstant;
import com.cong.fishisland.model.dto.ws.WSChannelExtraDTO;
import com.cong.fishisland.model.entity.chat.RoomMessage;
import com.cong.fishisland.model.entity.user.User;
import com.cong.fishisland.model.enums.MessageTypeEnum;
import com.cong.fishisland.model.vo.user.LoginUserVO;
import com.cong.fishisland.model.vo.user.UserMuteVO;
import com.cong.fishisland.model.vo.ws.ChatMessageVo;
import com.cong.fishisland.model.ws.request.Message;
import com.cong.fishisland.model.ws.request.MessageWrapper;
import com.cong.fishisland.model.ws.request.Sender;
import com.cong.fishisland.model.ws.request.WSBaseReq;
import com.cong.fishisland.model.ws.response.DrawPlayer;
import com.cong.fishisland.model.ws.response.UserChatResponse;
import com.cong.fishisland.model.ws.response.WSBaseResp;
import com.cong.fishisland.service.RoomMessageService;
import com.cong.fishisland.service.UserMuteService;
import com.cong.fishisland.service.UserService;
import com.cong.fishisland.websocket.cache.UserCache;
import com.cong.fishisland.websocket.event.AIAnswerEvent;
import com.cong.fishisland.websocket.event.AddSpeakPointEvent;
import com.cong.fishisland.websocket.event.UserOfflineEvent;
import com.cong.fishisland.websocket.event.UserOnlineEvent;
import com.cong.fishisland.websocket.service.WebSocketService;
import com.cong.fishisland.websocket.utils.NettyUtil;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import toolgood.words.StringSearch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;


/**
 * Description: websocket处理类
 * Date: 2023-03-19 16:21
 *
 * @author cong
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebSocketServiceImpl implements WebSocketService {
    /**
     * 所需服务
     */
    private final UserService userService;
    private final StringSearch wordsUtil;
    private final UserCache userCache;
    @Qualifier(ThreadPoolConfig.WS_EXECUTOR)
    private final ThreadPoolTaskExecutor threadPoolTaskExecutor;
    private final ApplicationEventPublisher applicationEventPublisher;

    private static final String ROOM_ID = "roomId";
    private final RoomMessageService roomMessageService;
    private final UserMuteService userMuteService;


    /**
     * 所有已连接的websocket连接列表和一些额外参数
     */
    private static final ConcurrentHashMap<Channel, WSChannelExtraDTO> ONLINE_WS_MAP = new ConcurrentHashMap<>();

    /**
     * 所有在线的用户和对应的socket
     */
    private static final ConcurrentHashMap<Long, CopyOnWriteArrayList<Channel>> ONLINE_UID_MAP = new ConcurrentHashMap<>();

    /**
     * 所有单人在线的棋局和对应的socket
     */
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Channel>> CHESS_ROOM_MAP = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Channel>> DRAW_ROOM_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<DrawPlayer>> DRAW_ROOM_PLAYER_MAP = new ConcurrentHashMap<>();

    @Override
    public void handleLoginReq(Channel channel) {
        try {
            String token = NettyUtil.getAttr(channel, NettyUtil.TOKEN);
            if (token == null) {
                return;
            }
            Object loginIdByToken = StpUtil.getLoginIdByToken(token);
            if (loginIdByToken == null) {
                return;
            }
            //更新上线列表
            online(channel, Long.valueOf((String) loginIdByToken));
            User loginUser = userService.getLoginUser(token);
            //发送用户上线事件
            boolean online = userCache.isOnline(loginUser.getId());
            if (!online) {
                loginUser.setUpdateTime(new Date());
                applicationEventPublisher.publishEvent(new UserOnlineEvent(this, loginUser));
            }
        } catch (Exception e) {
            log.error("websocket登录失败", e);
            channel.close();
        }

    }

    /**
     * 处理所有ws连接的事件
     *
     * @param channel 渠道
     */
    @Override
    public void connect(Channel channel) {
        log.info("websocket连接成功");
    }

    @Override
    public void removed(Channel channel) {
        WSChannelExtraDTO wsChannelExtraDTO = ONLINE_WS_MAP.get(channel);
        Optional<Long> uidOptional = Optional.ofNullable(wsChannelExtraDTO)
                .map(WSChannelExtraDTO::getUid);
        boolean offlineAll = offline(channel, uidOptional);
        if (uidOptional.isPresent() && offlineAll) {
            //已登录用户断连,并且全下线成功
            User user = new User();
            user.setId(uidOptional.get());
            applicationEventPublisher.publishEvent(new UserOfflineEvent(this, user));
            //推送其他用户下线事件
            //发送当前用户上线信息给所有人
            sendToAllOnline(WSBaseResp.builder()
                    .type(MessageTypeEnum.USER_OFFLINE.getType())
                    .data(uidOptional.get().toString()).build(), uidOptional.get());
        }
    }

    /**
     * 在线发送给所有人
     *
     * @param wsBaseResp WS基础研究
     * @param skipUid    跳过 UID
     */
    @Override
    public void sendToAllOnline(WSBaseResp<?> wsBaseResp, Long skipUid) {
        ONLINE_WS_MAP.forEach((channel, ext) -> {
            if (ObjectUtil.equal(ext.getUid(), skipUid)) {
                return;
            }
            threadPoolTaskExecutor.execute(() -> sendMsg(channel, wsBaseResp));
        });
    }

    @Override
    public void sendToAllOnline(WSBaseResp<?> wsBaseResp) {
        sendToAllOnline(wsBaseResp, null);
    }

    @Override
    public void sendToUid(WSBaseResp<?> wsBaseResp, Long uid) {
        CopyOnWriteArrayList<Channel> channels = ONLINE_UID_MAP.get(uid);
        if (CollUtil.isEmpty(channels)) {
            log.info("用户：{}不在线", uid);
            return;
        }
        channels.forEach(channel -> threadPoolTaskExecutor.execute(() -> sendMsg(channel, wsBaseResp)));
    }

    @Override
    public void sendMessage(Channel channel, WSBaseReq req) {
        // 发送数据
        String content = req.getData();
        ChatMessageVo chatMessageVo = JSONUtil.toBean(content, ChatMessageVo.class);
        // 接收消息 用户id
        Long uid = Long.valueOf(req.getUserId());
        String token = NettyUtil.getAttr(channel, NettyUtil.TOKEN);
        if (CharSequenceUtil.isEmpty(token)) {
            // 异常返回
            WSBaseResp<Object> errorResp = WSBaseResp.builder().type(MessageTypeEnum.ERROR.getType()).data(ErrorCode.FORBIDDEN_ERROR.getMessage()).build();
            sendMsg(channel, errorResp);
        }
        sendByType(chatMessageVo, token, uid, channel);

    }

    @Override
    public void sendMessage(String token, WSBaseReq req) {
        // 发送数据
        String content = req.getData();
        ChatMessageVo chatMessageVo = JSONUtil.toBean(content, ChatMessageVo.class);
        // 接收消息 用户id
        Long uid = Long.valueOf(req.getUserId());
        sendByType(chatMessageVo, token, uid, null);

    }

    @Override
    public List<UserChatResponse> getOnlineUserList() {
        return ONLINE_WS_MAP.values().stream()
                .map(WSChannelExtraDTO::getUserChatResponse)
                .collect(Collectors.toList());
    }

    private void sendByType(ChatMessageVo chatMessageVo, String token, Long uid, Channel channel) {
        long loginUserId = Long.parseLong(StpUtil.getLoginIdByToken(token).toString());
        User loginUser = userService.getLoginUser(token);
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.of(chatMessageVo.getType());
        //发送消息
        switch (messageTypeEnum) {
            case CHAT:
                MessageWrapper messageDto = JSON.parseObject(chatMessageVo.getContent(), MessageWrapper.class);
                Message message = messageDto.getMessage();
                String resultContent = fixMessage(message);
                message.setContent(resultContent);
                UserMuteVO userMuteInfo = userMuteService.getUserMuteInfo(loginUserId);
                if (userMuteInfo.getIsMuted()){
                    //用户被禁言
                    // 异常返回
                    WSBaseResp<Object> errorResp = WSBaseResp.builder().type(MessageTypeEnum.ERROR.getType()).data(userMuteInfo.getRemainingTime()).build();
                    sendMsg(channel, errorResp);
                    return;
                }
                applicationEventPublisher.publishEvent(new AddSpeakPointEvent(this, message.getSender().getId()));
                sendToAllOnline(WSBaseResp.builder()
                        .type(MessageTypeEnum.CHAT.getType())
                        .data(messageDto).build(), loginUserId);
                //查看是否是给机器人发的
                List<Sender> mentionedUsers = message.getMentionedUsers();

                if (mentionedUsers != null && !mentionedUsers.isEmpty()) {
                    //校验里面是否有机器人
                    boolean isRobot = mentionedUsers.stream().anyMatch(item -> item.getId().equals(UserConstant.ROBOT_ID));
                    if (isRobot) {
                        applicationEventPublisher.publishEvent(new AIAnswerEvent(this, messageDto));
                    }
                }

                //保存消息到数据库
                RoomMessage roomMessage = new RoomMessage();
                roomMessage.setUserId(loginUserId);
                roomMessage.setRoomId(-1L);
                roomMessage.setMessageJson(JSON.toJSONString(messageDto));
                roomMessage.setMessageId(messageDto.getMessage().getId());
                roomMessageService.save(roomMessage);
                break;
            case USER_MESSAGE_REVOKE:
                //撤回消息
                RoomMessage roomMess = roomMessageService.getOne(new LambdaQueryWrapper<RoomMessage>()
                        .eq(RoomMessage::getMessageId, chatMessageVo.getContent()));
                if (roomMess != null && (roomMess.getUserId() == loginUserId
                        || loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE))) {
                    roomMessageService.removeById(roomMess.getId());
                    //发送撤回消息
                    sendToAllOnline(WSBaseResp.builder()
                            .type(MessageTypeEnum.USER_MESSAGE_REVOKE.getType())
                            .data(chatMessageVo.getContent()).build());
                }

                break;
            case CREATE_CHESS_ROOM:
                //创建棋局房间
                createRoom(channel);
                break;
            case JOIN_ROOM:
                //加入棋局房间
                joinRoom(chatMessageVo, channel, loginUserId);
                break;
            case MOVE_CHESS:
                //移动棋子
                moveChess(chatMessageVo, uid);
                break;
            case CREATE_DRAW_ROOM:
                createDrawRoom(channel, loginUser);
                break;
            default:
                break;
        }
    }

    private @NotNull String fixMessage(Message message) {
        //敏感词替换
        String resultContent = wordsUtil.Replace(message.getContent());
        //移除代码高亮符号
        resultContent = resultContent.replaceAll("```", "");
        return resultContent;
    }

    private void createDrawRoom(Channel channel, User loginUser) {
        //自动生成房间号
        String roomId = String.valueOf(System.currentTimeMillis());
        DRAW_ROOM_MAP.putIfAbsent(roomId, new CopyOnWriteArrayList<>());
        DRAW_ROOM_MAP.get(roomId).add(channel);
        //加入房间用户列表
        DRAW_ROOM_PLAYER_MAP.putIfAbsent(roomId, new CopyOnWriteArrayList<>());
        DrawPlayer drawPlayer = new DrawPlayer();
        drawPlayer.setId(String.valueOf(loginUser.getId()));
        drawPlayer.setUserName(loginUser.getUserName());
        drawPlayer.setUserAvatar(loginUser.getUserAvatar());
        DRAW_ROOM_PLAYER_MAP.get(roomId).add(drawPlayer);
        //返回房间号
        WSBaseResp<Object> createResp = WSBaseResp.builder().type(MessageTypeEnum.ROOM_DRAW_CREATED.getType()).data(roomId).build();
        sendMsg(channel, createResp);
        //发送在线列表
        WSBaseResp<Object> createUserListResp = WSBaseResp.builder().type(MessageTypeEnum.ROOM_DRAW_USER_LIST.getType()).data(DRAW_ROOM_PLAYER_MAP.get(roomId)).build();
        sendMsg(channel, createUserListResp);
    }

    private void moveChess(ChatMessageVo chatMessageVo, Long uid) {
        CopyOnWriteArrayList<Channel> channels = ONLINE_UID_MAP.get(uid);
        JSONObject message = JSON.parseObject(chatMessageVo.getContent());
        message.put(ROOM_ID, message.get(ROOM_ID));
        WSBaseResp<Object> wsBaseResp = WSBaseResp.builder()
                .type(MessageTypeEnum.MOVE_CHESS.getType()).data(message).build();
        channels.forEach(item -> threadPoolTaskExecutor.execute(() -> sendMsg(item, wsBaseResp)));
    }

    private void createRoom(Channel channel) {
        //自动生成房间号
        String roomId = String.valueOf(System.currentTimeMillis());
        CHESS_ROOM_MAP.putIfAbsent(roomId, new CopyOnWriteArrayList<>());
        CHESS_ROOM_MAP.get(roomId).add(channel);
        //返回房间号
        WSBaseResp<Object> createResp = WSBaseResp.builder().type(MessageTypeEnum.CREATE_CHESS_ROOM.getType()).data(roomId).build();
        sendMsg(channel, createResp);
    }

    private void joinRoom(ChatMessageVo chatMessageVo, Channel channel, long loginUserId) {
        String joinRoomId = chatMessageVo.getContent();
        if (!CHESS_ROOM_MAP.containsKey(joinRoomId)) {
            WSBaseResp<Object> errorResp = WSBaseResp.builder().type(MessageTypeEnum.ERROR.getType()).data("房间不存在或已开始").build();
            sendMsg(channel, errorResp);
            return;
        }
        CopyOnWriteArrayList<Channel> channels = CHESS_ROOM_MAP.get(joinRoomId);
        CHESS_ROOM_MAP.remove(joinRoomId);

        //房主
        Channel roomOwner = channels.get(0);
        //把当前登录用户传给对方
        Map<String, Object> data = new HashMap<>();
        data.put(ROOM_ID, joinRoomId);
        data.put("playerId", String.valueOf(loginUserId));
        data.put("yourColor", "black");
        data.put("opponentColor", "white");
        sendMsg(roomOwner, WSBaseResp.builder().type(MessageTypeEnum.JOIN_SUCCESS.getType()).data(data).build());

        //把获取房主传给当前登录用户
        WSChannelExtraDTO wsChannelExtraDTO = ONLINE_WS_MAP.get(roomOwner);
        Map<String, Object> data2 = new HashMap<>();
        data2.put(ROOM_ID, joinRoomId);
        data2.put("playerId", String.valueOf(wsChannelExtraDTO.getUid()));
        data2.put("yourColor", "white");
        data2.put("opponentColor", "black");

        sendMsg(channel, WSBaseResp.builder().type(MessageTypeEnum.JOIN_SUCCESS.getType()).data(data2).build());
    }


    /**
     * 用户上线
     */
    private void online(Channel channel, Long uid) {
        if (ONLINE_WS_MAP.values().stream().anyMatch(item -> Objects.equals(item.getUid(), uid))) {
            return;
        }

        User currentUser = userService.getLoginUser(NettyUtil.getAttr(channel, NettyUtil.TOKEN));
        LoginUserVO loginUserVO = userService.getLoginUserVO(currentUser);

        UserChatResponse userChatResponse = new UserChatResponse();
        userChatResponse.setId(String.valueOf(currentUser.getId()));
        userChatResponse.setName(currentUser.getUserName());
        userChatResponse.setAvatar(currentUser.getUserAvatar());
        userChatResponse.setAvatarFramerUrl(currentUser.getAvatarFramerUrl());
        userChatResponse.setTitleId(currentUser.getTitleId());
        userChatResponse.setTitleIdList(currentUser.getTitleIdList());
        userChatResponse.setUserProfile(currentUser.getUserProfile());
        //目前为一级
        userChatResponse.setLevel(loginUserVO.getLevel());
        userChatResponse.setIsAdmin(currentUser.getUserRole().equals(UserConstant.ADMIN_ROLE) ?
                Boolean.TRUE : Boolean.FALSE);
        userChatResponse.setStatus("在线");
        userChatResponse.setPoints(loginUserVO.getPoints());

        WSChannelExtraDTO channelExt = getOrInitChannelExt(channel);
        channelExt.setUid(uid);
        channelExt.setUserChatResponse(userChatResponse);
        CopyOnWriteArrayList<Channel> arrayList = new CopyOnWriteArrayList<>();
        arrayList.add(channel);
        ONLINE_UID_MAP.putIfAbsent(uid, arrayList);
        WSChannelExtraDTO wsChannelExtraDTO = new WSChannelExtraDTO();
        wsChannelExtraDTO.setUid(uid);
        wsChannelExtraDTO.setUserChatResponse(userChatResponse);


        ONLINE_WS_MAP.put(channel, wsChannelExtraDTO);

        //发送当前用户上线信息给所有人
        sendToAllOnline(WSBaseResp.builder()
                .type(MessageTypeEnum.USER_ONLINE.getType())
                .data(Collections.singletonList(userChatResponse)).build(), uid);
    }

    /**
     * 如果在线列表不存在，就先把该channel放进在线列表
     *
     * @param channel 渠道
     * @return {@link WSChannelExtraDTO}
     */
    private WSChannelExtraDTO getOrInitChannelExt(Channel channel) {
        WSChannelExtraDTO wsChannelExtraDTO =
                ONLINE_WS_MAP.getOrDefault(channel, new WSChannelExtraDTO());
        WSChannelExtraDTO old = ONLINE_WS_MAP.putIfAbsent(channel, wsChannelExtraDTO);
        return ObjectUtil.isNull(old) ? wsChannelExtraDTO : old;
    }

    /**
     * 发送消息
     *
     * @param channel    渠道
     * @param wsBaseResp WS基础研究
     */
    private void sendMsg(Channel channel, WSBaseResp<?> wsBaseResp) {
        channel.writeAndFlush(new TextWebSocketFrame(JSONUtil.toJsonStr(wsBaseResp)));
    }

    /**
     * 用户下线
     * return 是否全下线成功
     */
    private boolean offline(Channel channel, Optional<Long> uidOptional) {
        ONLINE_WS_MAP.remove(channel);
        if (uidOptional.isPresent()) {
            CopyOnWriteArrayList<Channel> channels = ONLINE_UID_MAP.get(uidOptional.get());
            if (CollUtil.isNotEmpty(channels)) {
                channels.removeIf(channel1 -> channel1.equals(channel));
            }
            ONLINE_UID_MAP.remove(uidOptional.get());
            return CollUtil.isEmpty(ONLINE_UID_MAP.get(uidOptional.get()));
        }

        return true;
    }
}
