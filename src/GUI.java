package src;

import javax.net.ssl.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.Timer;

public class GUI {
    private JFrame frame;
    private JTextArea logArea;
    private JList<String> userList;
    private JList<String> mutedList;
    private JTextField commandField;
    private DefaultListModel<String> userListModel;
    private DefaultListModel<String> mutedListModel;

    public GUI() {
        initialize();
        setupLogRedirection();
        startStatusUpdater();
    }

    private void initialize() {
        frame = new JFrame("服务器管理控制台");
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));

        // 状态面板
        JPanel statusPanel = new JPanel(new GridLayout(1, 2));
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        statusPanel.add(new JScrollPane(userList), "在线用户");

        mutedListModel = new DefaultListModel<>();
        mutedList = new JList<>(mutedListModel);
        statusPanel.add(new JScrollPane(mutedList), "禁言用户");

        // 日志区域
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);

        // 命令面板
        JPanel commandPanel = new JPanel(new BorderLayout());
        commandField = new JTextField();
        JButton sendButton = new JButton("发送");
        commandPanel.add(commandField, BorderLayout.CENTER);
        commandPanel.add(sendButton, BorderLayout.EAST);

        // 布局组装
        mainPanel.add(statusPanel, BorderLayout.NORTH);
        mainPanel.add(logScroll, BorderLayout.CENTER);
        mainPanel.add(commandPanel, BorderLayout.SOUTH);

        frame.add(mainPanel);

        // 事件处理
        sendButton.addActionListener(this::handleCommand);
        commandField.addActionListener(this::handleCommand);

        frame.setVisible(true);
    }

    private void handleCommand(ActionEvent e) {
        String command = commandField.getText().trim();
        commandField.setText("");
        executeServerCommand(command);
    }

    private void executeServerCommand(String command) {
        if (command.startsWith("mute ")) {
            String[] parts = command.split("\\s+", 3);
            if (parts.length == 3) {
                src.Server.muteUser(parts[1], Integer.parseInt(parts[2]));
            }
        } else if (command.startsWith("unmute ")) {
            src.Server.unmuteUser(command.substring(7));
        } else if (command.startsWith("out ")) {
            src.Server.kickUser(command.substring(4));
        } else if (command.startsWith("announce ")) {
            src.Server.broadcast("[系统公告] " + command.substring(9), null);
        } else {
            src.Server.broadcast("[管理员] " + command, null);
        }
    }

    private void setupLogRedirection() {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                logArea.append(String.valueOf((char) b));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                logArea.append(new String(b, off, len));
            }
        };

        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));
    }

    private void startStatusUpdater() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> {
                    // 更新在线用户
                    userListModel.clear();
                    synchronized (src.Server.clients) {
                        src.Server.clients.stream()
                                .filter(c -> c.username != null)
                                .forEach(c -> userListModel.addElement(c.username));
                    }

                    // 更新禁言列表
                    mutedListModel.clear();
                    src.Server.mutedUsers.keySet().forEach(mutedListModel::addElement);
                });
            }
        }, 0, 1000);
    }

    public static void main(String[] args) {
        new GUI();

        // 启动服务器
        new Thread(() -> src.Server.main(new String[0])).start();
    }
}