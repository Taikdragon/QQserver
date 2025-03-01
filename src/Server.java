package src;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;

public class Server {
    private static final int PORT = 12345;
    private static final String USER_DB_FILE = "users.db";
    private static Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<>());
    private static Map<String, String> userDatabase = new ConcurrentHashMap<>();
    private static Map<String, String> userSecurity = new ConcurrentHashMap<>();

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:chat.db");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY," +
                    "sender TEXT," +
                    "receiver TEXT," +
                    "content TEXT," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void loadUserData() {
        try (BufferedReader reader = new BufferedReader(new FileReader(USER_DB_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 2) {
                    userDatabase.put(parts[0], parts[1]);
                    if (parts.length >= 3) {
                        userSecurity.put(parts[0], parts[2]);
                    }
                }
            }
            System.out.println("用户数据加载完成");
        } catch (IOException e) {
            System.out.println("用户数据文件未找到，将创建新文件");
        }
    }

    private static void saveUserData() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(USER_DB_FILE))) {
            for (String username : userDatabase.keySet()) {
                String passwordHash = userDatabase.get(username);
                String email = userSecurity.getOrDefault(username, "");
                writer.println(username + ":" + passwordHash + ":" + email);
            }
            System.out.println("用户数据已保存");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.setProperty("jdk.tls.server.protocols", "TLSv1.3");
        System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
        System.setProperty("javax.net.ssl.keyStorePassword", "nn0426");

        loadUserData();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> saveUserData()));

        System.out.println("QQ聊天服务器已启动...");
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            KeyStore ks = KeyStore.getInstance("JKS");

            try (FileInputStream fis = new FileInputStream("keystore.jks")) {
                ks.load(fis, "nn0426".toCharArray());
            }
            kmf.init(ks, "nn0426".toCharArray());
            sslContext.init(kmf.getKeyManagers(), null, null);

            SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
            SSLServerSocket serverSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(PORT);

            // 心跳检测线程（超时时间调整为5分钟）
            new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(30000);
                        synchronized (clients) {
                            Iterator<ClientHandler> it = clients.iterator();
                            while (it.hasNext()) {
                                ClientHandler client = it.next();
                                if (System.currentTimeMillis() - client.lastActiveTime > 300000) {
                                    client.socket.close();
                                    it.remove();
                                    System.out.println("心跳检测断开: " + client.username);
                                }
                            }
                        }
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                while (true) {
                    String command = scanner.nextLine();
                    if (command.startsWith("out ")) {
                        String targetUser = command.substring(4).trim();
                        kickUser(targetUser);
                    }
                }
            }).start();

            while (true) {
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                System.out.println("新客户端连接: " + clientSocket);
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                clients.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (IOException | NoSuchAlgorithmException | KeyManagementException | KeyStoreException |
                 UnrecoverableKeyException | CertificateException e) {
            System.err.println("服务器异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void kickUser(String username) {
        synchronized (clients) {
            Iterator<ClientHandler> iterator = clients.iterator();
            while (iterator.hasNext()) {
                ClientHandler client = iterator.next();
                if (client.username != null && client.username.equals(username)) {
                    try {
                        // 发送踢出消息并关闭连接
                        client.sendMessage("SYSTEM:你已被管理员踢出");
                        client.socket.close();
                        iterator.remove();
                        System.out.println("[管理员] 用户 " + username + " 已被踢出");
                        broadcastUserList(); // 更新所有用户的在线列表
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return;
                }
            }
            System.out.println("[错误] 用户 " + username + " 不存在或未在线");
        }
    }

    public static void broadcast(String message, ClientHandler excludeClient) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != excludeClient && client.username != null) {
                    client.sendMessage(message);
                }
            }
        }
    }

    public static void broadcastUserList() {
        List<String> usernames = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client.username != null) {
                    usernames.add(client.username);
                }
            }
        }
        String userListMsg = "USERS:" + String.join(",", usernames);
        broadcast(userListMsg, null);
    }

    private static class ClientHandler implements Runnable {
        private final SSLSocket socket;
        private PrintWriter out;
        private String username;
        private volatile long lastActiveTime = System.currentTimeMillis();

        public ClientHandler(Socket socket) {
            this.socket = (SSLSocket) socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                this.out = out;
                String command;
                while ((command = in.readLine()) != null) {
                    lastActiveTime = System.currentTimeMillis();
                    String[] parts = command.split(":", 4);

                    if (parts.length < 3) {
                        out.println("ERROR:命令格式错误");
                        continue;
                    }

                    String action = parts[0];
                    String username = parts[1];
                    String data = parts.length > 2 ? parts[2] : "";

                    switch (action) {
                        case "REGISTER":
                            handleRegister(username, data, out);
                            break;
                        case "LOGIN":
                            handleLogin(username, data, out);
                            break;
                        case "RESET_PASSWORD":
                            handleResetPassword(username, data, out);
                            break;
                        case "FIND_PASSWORD":
                            handleFindPassword(username, out);
                            break;
                        case "CHAT":
                            handleChatMessage(username, data);
                            break;
                        case "FILE":
                            //handleFileTransfer(username, parts);
                            //handleFileTransfer(username, data);
                            // 提取完整的FILE数据部分（格式：目标用户:编码文件名:内容）
                            String fileData = command.substring(5); // 移除"FILE:"前缀
                            handleFileTransfer(this.username, fileData);
                            break;
                        case "HEARTBEAT":
                            break;
                        default:
                            out.println("ERROR:未知命令");
                    }
                }
            } catch (IOException e) {
                System.out.println((username != null ? username : "未知用户") + " 的连接异常断开");
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
                clients.remove(this);
                if (username != null) {
                    broadcast(username + " 离开了聊天", null);
                    broadcastUserList();
                }
            }
        }

        private void handleRegister(String username, String passwordHash, PrintWriter out) {
            if (!userDatabase.containsKey(username)) {
                userDatabase.put(username, passwordHash);
                out.println("SUCCESS:注册成功");
                saveUserData();
                System.out.println(username + " 注册成功");
            } else {
                out.println("ERROR:用户名已存在");
            }
        }

        private void handleLogin(String username, String inputHash, PrintWriter out) {
            System.out.println("[调试] 登录请求 - 用户名: " + username + ", 输入哈希: " + inputHash);
            System.out.println("[调试] 数据库中的哈希: " + userDatabase.get(username));

            if (userDatabase.containsKey(username) && userDatabase.get(username).equals(inputHash)) {
                this.username = username;
                out.println("SUCCESS:登录成功");
                broadcast(username + " 加入了聊天", this);
                broadcastUserList();
                System.out.println(username + " 登录成功");
            } else {
                out.println("ERROR:用户名或密码错误");
                System.out.println("[调试] 登录失败: 用户名或密码错误");
            }
        }

        private void handleResetPassword(String username, String newHash, PrintWriter out) {
            if (userDatabase.containsKey(username)) {
                userDatabase.put(username, newHash);
                out.println("SUCCESS:密码重置成功");
                saveUserData();
                System.out.println(username + " 重置密码");
            } else {
                out.println("ERROR:用户不存在");
            }
        }

        private void handleFindPassword(String username, PrintWriter out) {
            String email = userSecurity.get(username);
            if (email != null) {
                out.println("SUCCESS:安全邮箱为 " + email);
            } else {
                out.println("ERROR:未设置安全邮箱");
            }
        }

        private void handleChatMessage(String username, String message) {
            if (this.username == null || !this.username.equals(username)) {
                out.println("ERROR:未登录或用户名不匹配");
                return;
            }

            if (message.startsWith("@")) {
                int spaceIndex = message.indexOf(" ");
                if (spaceIndex != -1) {
                    String targetUser = message.substring(1, spaceIndex);
                    String privateMsg = message.substring(spaceIndex + 1);
                    sendPrivateMessage(username, targetUser, privateMsg);
                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:chat.db");
                         PreparedStatement pstmt = conn.prepareStatement(
                                 "INSERT INTO messages(sender, receiver, content) VALUES(?,?,?)")) {
                        pstmt.setString(1, username);
                        pstmt.setString(2, targetUser);
                        pstmt.setString(3, privateMsg);
                        pstmt.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                broadcast("[" + username + "]: " + message, this);
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:chat.db");
                     PreparedStatement pstmt = conn.prepareStatement(
                             "INSERT INTO messages(sender, receiver, content) VALUES(?,?,?)")) {
                    pstmt.setString(1, username);
                    pstmt.setString(2, null);
                    pstmt.setString(3, message);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        private void sendPrivateMessage(String sender, String targetUser, String message) {
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client.username != null && client.username.equals(targetUser)) {
                        client.sendMessage("[私聊来自 " + sender + "]: " + message);
                        this.sendMessage("[私聊发给 " + targetUser + "]: " + message);
                        return;
                    }
                }
            }
            this.sendMessage("ERROR:用户 " + targetUser + " 不在线");
        }

        /*
        private void handleFileTransfer(String sender, String data) {
            String[] parts = data.split(":", 3);
            if (parts.length < 3) {
                sendMessage("ERROR:文件参数格式错误");
                return;
            }
            try {
                String targetUser = parts[0];
                String fileName = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                String fileContent = parts[2];

                synchronized (clients) {
                    for (ClientHandler client : clients) {
                        if (client.username != null && client.username.equals(targetUser)) {
                            client.sendMessage("FILE:" + sender + ":" + fileName + ":" + fileContent);
                            return;
                        }
                    }
                }
                this.sendMessage("ERROR:用户 " + targetUser + " 不在线");
            } catch (IllegalArgumentException e) {
                sendMessage("ERROR:文件名解码失败");
            }
        }

         */

        private void handleFileTransfer(String sender, String data) {
            try {
                System.out.println("[DEBUG] 收到原始数据: " + data);
                String[] parts = data.split(":", 3); // 分割为 [目标用户, 文件名, 内容]
                String targetUser = parts[0];
                String encodedFileName = parts[1];
                String fileContent = parts[2];
                // 转发消息给目标用户（格式: FILE:发送者:文件名:内容）
                String forwardMsg = "FILE:" + sender + ":" + targetUser + ":" + encodedFileName + ":" +fileContent;
                if (parts.length < 3) {
                    System.out.println("[ERROR] 文件参数不足，实际字段数: " + parts.length);
                    sendMessage("ERROR:文件参数格式错误");
                    return;
                }

                //String targetUser = parts[0];
                String fileName = new String(
                        Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8
                );
                //String fileContent = parts[2];

                synchronized (clients) {
                    for (ClientHandler client : clients) {
                        if (client.username != null && client.username.equals(targetUser)) {
                            client.sendMessage(forwardMsg);
                            sendMessage("发送成功");
                            System.out.println("[DEBUG] 已转发文件：" + fileName + " -> " + targetUser);
                            return;
                        }
                    }
                }
                sendMessage("ERROR:用户 " + targetUser + " 不在线");
            } catch (IllegalArgumentException e) {
                System.out.println("[ERROR] 文件名解码失败: " + e.getMessage());
                sendMessage("ERROR:文件格式非法");
            } catch (Exception e) {
                System.out.println("[ERROR] 文件处理异常: " + e.getMessage());
                sendMessage("ERROR:文件处理失败");
                e.printStackTrace();
            }
        }

        public void sendMessage(String message) {
            out.println(message);
        }
    }

    public static String hashPassword(String password) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashedBytes = md.digest(password.getBytes());
        return Base64.getEncoder().encodeToString(hashedBytes);
    }
}