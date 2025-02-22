package src;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Server {
    private static final int PORT = 12345;
    private static final String USER_DB_FILE = "users.db";
    private static Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<>());

    // 用户数据库：用户名 -> 密码哈希
    private static Map<String, String> userDatabase = new ConcurrentHashMap<>();
    // 用户安全信息：用户名 -> 安全邮箱（示例）
    private static Map<String, String> userSecurity = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        loadUserData();  // 启动时加载用户数据
        Runtime.getRuntime().addShutdownHook(new Thread(() -> saveUserData()));  // 关闭时保存数据

        System.out.println("QQ聊天服务器已启动...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("新客户端连接: " + clientSocket);
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                clients.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("服务器异常: " + e.getMessage());
        }
    }

    // 加载用户数据
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

    // 保存用户数据
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

    // 广播消息给所有客户端
    public static void broadcast(String message, ClientHandler excludeClient) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != excludeClient) {
                    client.sendMessage(message);
                }
            }
        }
    }

    // 客户端处理线程
    private static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private String username;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                this.out = out;

                // 处理客户端命令
                String command;
                while ((command = in.readLine()) != null) {
                    String[] parts = command.split(":", 3);
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
                        default:
                            out.println("ERROR:未知命令");
                    }
                }

            } catch (IOException e) {
                System.out.println(username + " 的连接异常断开");
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
                clients.remove(this);
                System.out.println(username + " 已断开连接");
            }
        }

        // 处理注册
        private void handleRegister(String username, String passwordHash, PrintWriter out) {
            if (!userDatabase.containsKey(username)) {
                userDatabase.put(username, passwordHash);
                out.println("SUCCESS:注册成功");
                System.out.println(username + " 注册成功");
            } else {
                out.println("ERROR:用户名已存在");
            }
        }

        // 处理登录
        private void handleLogin(String username, String inputHash, PrintWriter out) {
            if (userDatabase.containsKey(username) && userDatabase.get(username).equals(inputHash)) {
                this.username = username;
                out.println("SUCCESS:登录成功");
                System.out.println(username + " 登录成功");
            } else {
                out.println("ERROR:用户名或密码错误");
            }
        }

        // 处理密码重置
        private void handleResetPassword(String username, String newHash, PrintWriter out) {
            if (userDatabase.containsKey(username)) {
                userDatabase.put(username, newHash);
                out.println("SUCCESS:密码重置成功");
                System.out.println(username + " 重置密码");
            } else {
                out.println("ERROR:用户不存在");
            }
        }

        // 处理找回密码（示例：返回安全邮箱）
        private void handleFindPassword(String username, PrintWriter out) {
            String email = userSecurity.get(username);
            if (email != null) {
                out.println("SUCCESS:安全邮箱为 " + email);
            } else {
                out.println("ERROR:未设置安全邮箱");
            }
        }

        public void sendMessage(String message) {
            out.println(message);
        }
    }

    // 密码哈希工具方法（供客户端调用）
    public static String hashPassword(String password) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashedBytes = md.digest(password.getBytes());
        return Base64.getEncoder().encodeToString(hashedBytes);
    }
}