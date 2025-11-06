/*
 * Copyright (c) 2025 AzSuawa. All rights reserved. 
 * 版权所有 (c) 2025 AzSuawa 保留所有权利.
 *
 * Licensed under MPL 2.0. See: http://mozilla.org/MPL/2.0/
 * 基于Mozilla公共许可证2.0版开源。许可证: http://mozilla.org/MPL/2.0/
 *
 * Project/项目: https://github.com/AzSuawa/AzSuVelocity
 */

package top.azsu.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.command.CommandSource;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@com.velocitypowered.api.plugin.Plugin(
    id = "azsu-velocity",
    name = "AzSuVelocity",
    version = "1.0.0",
    authors = {"azsu"},
    description = "AzSu跨服命令转发Velocity端处理器"
)
public class AzSuVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    
    private static final MinecraftChannelIdentifier AZSU_CHANNEL = 
        MinecraftChannelIdentifier.from("azsu:main");

    @Inject
    public AzSuVelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // 注册消息通道
        server.getChannelRegistrar().register(AZSU_CHANNEL);
        
        logger.info("AzSuVelocity插件已启用！版本: 1.0.0");
        logger.info("已注册消息通道: {}", AZSU_CHANNEL.getId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getIdentifier().equals(AZSU_CHANNEL) && event.getSource() instanceof ServerConnection) {
            ServerConnection connection = (ServerConnection) event.getSource();
            
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
                
                // 解析消息格式
                String targetServer = in.readUTF();
                String command = in.readUTF();
                String executorName = in.readUTF();
                String executorUUID = in.readUTF();
                boolean executeAsConsole = in.readBoolean();
                
                logger.info("接收命令请求: {} -> {} (执行者: {}, 控制台: {})", 
                    command, targetServer, executorName, executeAsConsole);
                
                // 处理命令转发
                handleCommandForward(targetServer, command, executorName, executorUUID, executeAsConsole);
                
                // 标记为已处理
                event.setResult(PluginMessageEvent.ForwardResult.handled());
                
            } catch (IOException e) {
                logger.error("处理插件消息失败", e);
            }
        }
    }

    /**
     * 处理命令转发
     */
    private void handleCommandForward(String targetServer, String command, 
                                    String executorName, String executorUUID, 
                                    boolean executeAsConsole) {
        try {
            CommandSource executor = getCommandExecutor(executorName, executorUUID, executeAsConsole);
            if (executor == null) {
                logger.warn("无法找到命令执行者: {} ({})", executorName, executorUUID);
                return;
            }
            
            // 根据目标服务器执行命令
            if ("all".equalsIgnoreCase(targetServer)) {
                // 向所有服务器执行命令
                executeCommandAllServers(executor, command);
            } else if ("proxy".equalsIgnoreCase(targetServer) || "velocity".equalsIgnoreCase(targetServer)) {
                // 在代理端执行命令
                executeCommandProxy(executor, command);
            } else {
                // 向特定服务器执行命令
                executeCommandSpecificServer(executor, command, targetServer);
            }
            
            logger.info("命令执行完成: {} -> {}", command, targetServer);
            
        } catch (Exception e) {
            logger.error("处理命令转发失败: {}", command, e);
        }
    }

    /**
     * 获取命令执行者
     */
    private CommandSource getCommandExecutor(String executorName, String executorUUID, boolean executeAsConsole) {
        if (executeAsConsole || "CONSOLE".equals(executorName)) {
            return server.getConsoleCommandSource();
        } else {
            // 对于player模式，尝试查找玩家
            try {
                UUID uuid = UUID.fromString(executorUUID);
                Optional<Player> player = server.getPlayer(uuid);
                if (player.isPresent()) {
                    return player.get();
                } else {
                    logger.warn("玩家不在线: {} ({})", executorName, executorUUID);
                    return null;
                }
            } catch (IllegalArgumentException e) {
                logger.warn("无效的UUID格式: {}", executorUUID);
                return null;
            }
        }
    }

    /**
     * 在代理端执行命令
     */
    private void executeCommandProxy(CommandSource executor, String command) {
        logger.info("在代理端执行命令: {} (执行者: {})", command, getExecutorName(executor));
        
        // 直接执行命令
        CompletableFuture<Boolean> future = server.getCommandManager().executeAsync(executor, command);
        future.thenAccept(success -> {
            if (success) {
                logger.info("代理端命令执行成功: {}", command);
            } else {
                logger.warn("代理端命令执行失败: {}", command);
            }
        }).exceptionally(throwable -> {
            logger.error("代理端命令执行异常: {}", command, throwable);
            return null;
        });
    }

    /**
     * 向特定服务器执行命令
     */
    private void executeCommandSpecificServer(CommandSource executor, String command, String serverName) {
        Optional<RegisteredServer> targetServer = server.getServer(serverName);
        if (targetServer.isPresent()) {
            logger.info("向服务器 {} 执行命令: {} (执行者: {})", 
                serverName, command, getExecutorName(executor));
            
            // 发送插件消息到目标服务器
            byte[] message = createCommandMessage(command, executor);
            if (message.length > 0) {
                boolean sent = targetServer.get().sendPluginMessage(AZSU_CHANNEL, message);
                if (sent) {
                    logger.info("✓ 消息成功发送到服务器: {}", serverName);
                } else {
                    logger.warn("✗ 消息发送到服务器失败: {}", serverName);
                }
            } else {
                logger.warn("创建的消息为空，无法发送到服务器: {}", serverName);
            }
            
        } else {
            logger.warn("目标服务器不存在: {}", serverName);
        }
    }

    /**
     * 向所有服务器执行命令
     */
    private void executeCommandAllServers(CommandSource executor, String command) {
        logger.info("向所有服务器执行命令: {} (执行者: {})", 
            command, getExecutorName(executor));
        
        int successCount = 0;
        int totalServers = 0;
        
        for (RegisteredServer server : server.getAllServers()) {
            totalServers++;
            byte[] message = createCommandMessage(command, executor);
            if (message.length > 0) {
                boolean sent = server.sendPluginMessage(AZSU_CHANNEL, message);
                if (sent) {
                    successCount++;
                    logger.debug("消息成功发送到服务器: {}", server.getServerInfo().getName());
                } else {
                    logger.warn("消息发送到服务器失败: {}", server.getServerInfo().getName());
                }
            }
        }
        
        logger.info("向所有服务器发送完成: 成功 {}/{}", successCount, totalServers);
    }

    /**
     * 创建命令消息
     */
    private byte[] createCommandMessage(String command, CommandSource executor) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            
            String executorName;
            String executorUUID;
            boolean executeAsConsole;
            
            if (executor instanceof Player) {
                Player player = (Player) executor;
                executorName = player.getUsername();
                executorUUID = player.getUniqueId().toString();
                executeAsConsole = false;
            } else {
                executorName = "CONSOLE";
                executorUUID = "CONSOLE";
                executeAsConsole = true;
            }
            
            out.writeUTF(command);
            out.writeUTF(executorName);
            out.writeUTF(executorUUID);
            out.writeBoolean(executeAsConsole);
            
            return bytes.toByteArray();
        } catch (IOException e) {
            logger.error("创建命令消息失败", e);
            return new byte[0];
        }
    }

    /**
     * 获取执行者名称
     */
    private String getExecutorName(CommandSource executor) {
        if (executor instanceof Player) {
            return ((Player) executor).getUsername();
        } else {
            return "CONSOLE";
        }
    }
}