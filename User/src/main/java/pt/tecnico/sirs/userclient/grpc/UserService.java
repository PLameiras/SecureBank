package pt.tecnico.sirs.userclient.grpc;

import io.grpc.*;
import pt.tecnico.sirs.contract.bankserver.BankServer;
import pt.tecnico.sirs.contract.bankserver.BankingServiceGrpc;
import pt.tecnico.sirs.contract.authenticationserver.AuthenticationServer;
import pt.tecnico.sirs.contract.authenticationserver.AuthenticationServerServiceGrpc;
import pt.tecnico.sirs.contract.bankserver.BankServer.*;
import pt.tecnico.sirs.cryptology.Base;
import pt.tecnico.sirs.cryptology.Operations;
import pt.tecnico.sirs.userclient.grpc.crypto.BankingClientCryptographicManager;
import pt.tecnico.sirs.utils.Utils;
import com.google.protobuf.ByteString;
import pt.tecnico.sirs.utils.exceptions.TamperedMessageException;

import java.io.*;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Files;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

public class UserService {
    public static class UserServiceBuilder {
        private final boolean debug;
        private final String host;
        private final Integer port;
        private final BankingClientCryptographicManager crypto;
        private final ChannelCredentials credentials;
        private ManagedChannel bankChannel;
        private ManagedChannel authenticationServerChannel;
        String authenticationServerAddress;
        Integer authenticationServerPort;

        public UserServiceBuilder(
                String host,
                Integer port,
                String authenticationServerAddress,
                Integer authenticationServerPort,
                String ivPath,
                String secretKeyPath,
                String publicKeyPath,
                String privateKeyPath,
                String trustCertCollectionPath,
                boolean debug
        ) throws Exception {
            this.debug = debug;
            this.host = host;
            this.port = port;
            this.authenticationServerAddress = authenticationServerAddress;
            this.authenticationServerPort = authenticationServerPort;
            this.crypto = new BankingClientCryptographicManager(
                    ivPath,
                    secretKeyPath,
                    publicKeyPath,
                    privateKeyPath
            );
            this.credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(new File(trustCertCollectionPath))
                    .build();
        }

        public UserService build() {
            this.authenticationServerChannel = Grpc.newChannelBuilderForAddress(
                            this.authenticationServerAddress,
                            this.authenticationServerPort,
                            this.credentials
            ).build();

            this.bankChannel = Grpc.newChannelBuilderForAddress(
                            this.host,
                            this.port,
                            this.credentials
            ).build();

            return new UserService(this);
        }
    }

    private final boolean debug;
    private final BankingClientCryptographicManager crypto;
    private final AuthenticationServerServiceGrpc.AuthenticationServerServiceBlockingStub authenticationServerServiceStub;
    private final BankingServiceGrpc.BankingServiceBlockingStub bankingServiceStub;
    private final Logger logger;

    private UserService(UserServiceBuilder builder) {
        this.crypto = builder.crypto;
        this.debug = builder.debug;
        this.logger = Logger.getLogger("UserService");
        this.authenticationServerServiceStub = AuthenticationServerServiceGrpc.newBlockingStub(builder.authenticationServerChannel);
        this.bankingServiceStub = BankingServiceGrpc.newBlockingStub(builder.bankChannel);
        this.authenticate(OffsetDateTime.now().toString());
    }

    public void authenticate(String timestampString) {
        try {
            // Needham-Schroeder step 1
            AuthenticationServer.AuthenticateResponse ticketResponse =
                authenticationServerServiceStub.authenticate(
                    AuthenticationServer.AuthenticateRequest.newBuilder().setRequest(
                        ByteString.copyFrom(
                            Utils.serializeJson(
                                Utils.createJson(
                                    List.of("source", "target", "timestampString"),
                                    List.of("user", "database", timestampString)
            )))).build());

            // Needham-Schroeder step 2
            JsonObject ticketJson = Utils.deserializeJson(
                Operations.decryptData(
                    Base.readSecretKey("resources/crypto/client/symmetricKey"),
                    ticketResponse.getResponse().toByteArray(),
                    Base.readIv("resources/crypto/client/iv")
            ));

            if (!ticketJson.getString("target").equals("database") || !ticketJson.getString("timestampString").equals(timestampString))
                throw new TamperedMessageException();
            // Save session key and session iv
            Files.write(Paths.get("resources/crypto/session/sessionKey"), Utils.hexToByte(ticketJson.getString("sessionKey")));
            Files.write(Paths.get("resources/crypto/session/iv"), Utils.hexToByte(ticketJson.getString("sessionIv")));

            // Needham-Schroeder step 3
            BankServer.AuthenticateResponse authenticateDatabaseResponse =
                bankingServiceStub.authenticate(BankServer.AuthenticateRequest.newBuilder().setRequest(
                    ByteString.copyFrom(
                        Utils.serializeJson(
                            Json.createObjectBuilder()
                                .add("ticket", ticketJson.getJsonString("targetTicket"))
                                .add("timestampString", timestampString)
                                .build()
            ))).build());

            // Needham-Schroeder steps 4 and 5
            StillAliveResponse ignored = bankingServiceStub.stillAlive(StillAliveRequest.newBuilder().setRequest(
                ByteString.copyFrom(
                    Operations.encryptData(
                        Base.readSecretKey("resources/crypto/session/sessionKey"),
                        Utils.serializeJson(
                            Json.createObjectBuilder()
                                .add(
                                    "nonce",
                                    Utils.deserializeJson(Operations.decryptData(
                                        Base.readSecretKey("resources/crypto/session/sessionKey"),
                                        authenticateDatabaseResponse.getResponse().toByteArray(),
                                        Base.readIv("resources/crypto/session/iv")
                                    )).getInt("nonce") - 1
                                ).build()),
                        Base.readIv("resources/crypto/session/iv")
            ))).build());
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

    public void createAccount(List<String> usernames, List<String> passwords, String timestampString) {
        try {
            if (debug) System.out.println("\tUserService: encoding create account request");
            
            JsonArrayBuilder jsonArrayBuilder = Json.createArrayBuilder();
            for (String username : usernames) {
                jsonArrayBuilder.add(username);
            }
            JsonArray usernamesJson = jsonArrayBuilder.build();

            jsonArrayBuilder = Json.createArrayBuilder();
            for (String password : passwords) {
                jsonArrayBuilder.add(crypto.encryptPassword(password));
            }
            JsonArray passwordsJson = jsonArrayBuilder.build();


            byte[] requestJson = Utils.serializeJson(Json.createObjectBuilder()
                .add("usernames", usernamesJson)
                .add("passwords", passwordsJson)
                .add("timestampString", timestampString)
                .build()
            );
 
            if (debug) System.out.println("\tUserService: making rpc");

            CreateAccountResponse ignored = bankingServiceStub.createAccount(crypto.encrypt(
                CreateAccountRequest.newBuilder()
                .setRequest(
                    ByteString.copyFrom(requestJson)
                ).build())
            );

            if (debug) System.out.println("\tUserService: processing create account response");
        } catch (StatusRuntimeException e) {
            System.out.println(e.getMessage());
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

    public void deleteAccount(String username, String password, String timestampString) {
        try {
            if (debug) System.out.println("\tUserService: encoding delete account request");
            byte[] requestJson = Utils.serializeJson(
                Utils.createJson(
                    List.of("username", "password", "timestampString"),
                    List.of(username, crypto.encryptPassword(password), timestampString)
            ));

            if (debug) System.out.println("\tUserService: making rpc");
            DeleteAccountResponse ignored = bankingServiceStub.deleteAccount(crypto.encrypt(
                DeleteAccountRequest.newBuilder()
                .setRequest(
                    ByteString.copyFrom(requestJson)
                ).build())
            );

            if (debug) System.out.println("\tUserService: processing delete account response");
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

    public void balance(String username, String password, String timestampString) {
        try {
            if (debug) System.out.println("\tUserService: encoding delete account request");
            byte[] requestJson = Utils.serializeJson(
                Utils.createJson(
                    List.of("username", "password", "timestampString"),
                    List.of(username, crypto.encryptPassword(password), timestampString)
            ));

            if (debug) System.out.println("\tUserService: making rpc");

            BalanceResponse balanceResponse = bankingServiceStub.balance(crypto.encrypt(
                BalanceRequest.newBuilder()
                .setRequest(
                    ByteString.copyFrom(requestJson)
                ).build())
            );

            if (!crypto.check(balanceResponse)) throw new RuntimeException("Message contents were tampered with");

            if (debug) System.out.println("\tUserService: processing balance response");
            JsonObject responseJson = Utils.deserializeJson(crypto.decrypt(balanceResponse).getResponse().toByteArray());
            System.out.println(responseJson.getString("balance"));
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

    public void getMovements(String username, String password, String timestampString) {
        try {
            if (debug) System.out.println("\tUserService: encoding show expenses request");
            byte[] requestJson = Utils.serializeJson(
                Utils.createJson(
                    List.of("username", "password", "timestampString"),
                    List.of(username, crypto.encryptPassword(password), timestampString)
            ));

            if (debug) System.out.println("\tUserService: making rpc");

            GetMovementsResponse getAccountMovementsResponse = bankingServiceStub.getMovements(crypto.encrypt(
                GetMovementsRequest.newBuilder()
                .setRequest(
                    ByteString.copyFrom(requestJson)
                ).build())
            );

            if (!crypto.check(getAccountMovementsResponse)) throw new RuntimeException("Message contents were tampered with");

            if (debug) System.out.println("\tUserService: processing get account movements response");
            JsonObject responseJson = Utils.deserializeJson(crypto.decrypt(getAccountMovementsResponse).getResponse().toByteArray());
            for(int i = 0; i < responseJson.getJsonArray("movements").size(); i++) {
                JsonObject movement = responseJson.getJsonArray("movements").getJsonObject(i);
                System.out.printf("Movement %d\n\tCurrency: %s\n\tDate: %s\n\tValue: %s\n\tDescription: %s\n", i + 1, movement.getString("currency"), movement.getString("date"), movement.getString("value"), movement.getString("description"));
            }
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

    public void addExpense(String username, String password, String date, String amount, String description, String timestampString) {
        try {
            if (debug) System.out.println("\tUserService: encoding add expense request");
            byte[] requestJson = Utils.serializeJson(
                Utils.createJson(
                    List.of("username", "password", "date", "amount", "description", "timestampString"),
                    List.of(username, crypto.encryptPassword(password), date, amount, description, timestampString)
            ));

            if (debug) System.out.println("\tUserService: making rpc");
            AddExpenseResponse ignored = bankingServiceStub.addExpense(crypto.encrypt(
                AddExpenseRequest.newBuilder()
                .setRequest(
                    ByteString.copyFrom(requestJson)
                ).build())
            );

            if (debug) System.out.println("\tUserService: processing add expense response");
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

    public void paymentOrder(String username, String password, String date, String amount, String description, String recipient, String timestampString) {
        try {
            if (debug) System.out.println("\tUserService: encoding payment order request");
            byte[] requestJson = Utils.serializeJson(
                Utils.createJson(
                    List.of("username", "password", "date", "amount", "description", "recipient", "timestampString"),
                    List.of(username, crypto.encryptPassword(password), date, amount, description, recipient, timestampString)
            ));

            if (debug) System.out.println("\tUserService: making rpc");
            OrderPaymentResponse ignored = bankingServiceStub.orderPayment(crypto.encrypt(
                OrderPaymentRequest.newBuilder()
                .setRequest(
                    ByteString.copyFrom(requestJson)
                ).build())
            );

            if (debug) System.out.println("\tUserService: processing payment order response");
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        } catch (Exception e) {
            logger.log(Level.SEVERE, Arrays.toString(e.getStackTrace()), e);
        }
    }

}