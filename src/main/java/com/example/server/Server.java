package com.example.server;

import com.example.controllers.BankController;
import com.example.exceptions.*;
import com.example.models.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.security.sasl.AuthenticationException;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

@Component
public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private ServerSocket server;
    private final Scanner scanner = new Scanner(System.in);
    private final BankController bankController;
    private String url;

    public Server(BankController bankController) {
        this.bankController = bankController;
    }

    public void setAddress(String[] address) {
        int port = Integer.parseInt(address[1]);
        try {
            this.server = new ServerSocket(port);
            this.url = address[0];
        } catch (IOException e) {
            logger.error("Error while starting server.");
        }
    }

    public void run() {
        System.out.println("Starting server. For exiting write \"stop\"");
        while (true) {
            try {
                Socket clientSocket = server.accept();
                new ClientThread(clientSocket).start();
            } catch (IOException e) {
                logger.error("Error while connecting client");
            }
        }
    }

    private class ClientThread extends Thread {
        private final Socket clientSocket;
        private DataOutputStream out;
        private BufferedReader in;
        private Map<String, String> requestHeaders;
        private String requestBody;
        private Response response;

        public ClientThread(Socket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            this.out = new DataOutputStream(clientSocket.getOutputStream());
            this.in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            logger.info("New client connected");
        }

        public void run() {
            try (DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream()); BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                this.out = out;
                this.in = in;
                handleHttpRequest();
            } catch (IOException e) {
                System.err.println("Error while connecting client.");
            }
        }

        private void handleHttpRequest() throws IOException {
            try {
                parseStartLine();
                parseHeader();
                readBody();
                implementMethod();
            } catch (InvalidRequestException | NotEnoughMoneyException | IllegalArgumentException ex) {
                response = new Response(400, "Bad Request", "{\"message\": \"" + ex.getMessage() + "\"}");
            } catch (ResourceNotFoundException | NoSuchUserException ex) {
                response = new Response(404, "Not Found", "{\"message\": \"" + ex.getMessage() + "\"}");
            } catch (MethodNotAllowedException ex) {
                response = new Response(405, "Method Not Allowed", "{\"message\": \"" + ex.getMessage() + "\"}");
            } catch (JwtAuthenticationException | AuthenticationException ex) {
                response = new Response(403, "Forbidden ", "{\"message\": \"" + ex.getMessage() + "\"}");
            } catch (UserAlreadyExistsException ex) {
                response = new Response(409, "Conflict", "{\"message\": \"" + ex.getMessage() + "\"}");
            }
            sendResponse();
        }

        private void parseStartLine() throws IOException, InvalidRequestException {
            requestHeaders = new HashMap<>();
            String request = in.readLine();
            String[] parts = request.split(" ");
            try {
                requestHeaders.put("method", parts[0]);
                String[] uri = parts[1].split("/");
                if (uri[0].equals(url) || uri[0].isEmpty()) {
                    requestHeaders.put("path", uri[1]);
                } else {
                    throw new InvalidRequestException("Unknown request URL.");
                }
            } catch (IndexOutOfBoundsException | NullPointerException ex) {
                throw new InvalidRequestException("Invalid http request start line.");
            }
        }

        private void parseHeader() throws IOException, InvalidRequestException {
            String request;
            while (!(request = in.readLine()).isEmpty()) {

                String[] parts = request.split(": ");
                try {
                    requestHeaders.put(parts[0], parts[1]);
                    String[] uri = parts[1].split("/");
                } catch (IndexOutOfBoundsException | NullPointerException ex) {
                    throw new InvalidRequestException("Invalid http header line.");
                }
            }
        }

        private void implementMethod() throws JwtAuthenticationException, IOException, InvalidRequestException, ResourceNotFoundException, MethodNotAllowedException, AuthenticationException, UserAlreadyExistsException, NotEnoughMoneyException {
            String method = requestHeaders.get("method");
            switch (method.toUpperCase()) {
                case "GET":
                    handleGetRequest();
                    break;
                case "POST":
                    handlePostRequest();
                    break;
                default:
                    throw new MethodNotAllowedException("Method " + method + " not allowed.");
            }
        }

        private void handleGetRequest() throws ResourceNotFoundException, JwtAuthenticationException, IOException {
            String path = requestHeaders.get("path");
            if (!path.equals("money")) {
                throw new ResourceNotFoundException("Resource not found \"" + path + "\"");
            }
            response = bankController.getBalance(new Request(requestHeaders));
        }

        private void handlePostRequest() throws InvalidRequestException, ResourceNotFoundException, IOException, org.springframework.security.core.AuthenticationException, UserAlreadyExistsException, NotEnoughMoneyException, AuthenticationException {
            if (requestBody.isEmpty()) {
                throw new InvalidRequestException("Body is empty");
            }
            String path = requestHeaders.get("path");
            try {
                switch (path) {
                    case "money":
                        response = bankController.transferMoney(new TransferRequest(requestBody, requestHeaders));
                        break;
                    case "signup":
                        response = bankController.signup(new SignupRequest(requestBody));
                        break;
                    case "signin":
                        response = bankController.signin(new SigninRequest(requestBody));
                        break;
                    default:
                        throw new ResourceNotFoundException("Resource not found \"" + path + "\"");
                }
            } catch (JsonProcessingException ex) {
                throw new InvalidRequestException("Error while serialization body");
            }

        }

        private void readBody() throws IOException {
            if (requestHeaders.get("Content-Length") == null) {
                return;
            }
            StringBuilder bodyBuilder = new StringBuilder();
            int length = Integer.parseInt(requestHeaders.get("Content-Length"));
            for (int i = 0; i < length; i++) {
                bodyBuilder.append((char) in.read());
            }
            requestBody = bodyBuilder.toString();
        }

        private void sendResponse() throws IOException {
            String responseStr = "HTTP/1.1 " + response.getStatus() + " " + response.getStatusMessage() + "\r\nContent-Type: application/json\r\nContent-Length: " + response.getBody().length() + "\r\n\r\n" + response.getBody();

            out.write(responseStr.getBytes());
        }
    }

    public void close() {

    }
}
