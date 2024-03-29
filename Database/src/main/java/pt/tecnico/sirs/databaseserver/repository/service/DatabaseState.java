package pt.tecnico.sirs.databaseserver.repository.service;

import org.hibernate.SessionFactory;
import pt.tecnico.sirs.databaseserver.dto.BankAccountDto;
import pt.tecnico.sirs.databaseserver.dto.MovementDto;
import pt.tecnico.sirs.databaseserver.dto.PaymentDto;
import pt.tecnico.sirs.databaseserver.repository.DatabaseOperations;
import pt.tecnico.sirs.databaseserver.repository.exceptions.WrongPasswordException;
import pt.tecnico.sirs.databaseserver.repository.service.engine.*;
import pt.tecnico.sirs.databaseserver.repository.service.engine.impl.*;
import pt.tecnico.sirs.utils.exceptions.ReplayAttackException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

import javax.json.Json;
import javax.json.JsonArrayBuilder;

public class DatabaseState implements DatabaseOperations {
    public static final class DatabaseManagerBuilder {
        private final BankAccountService bankAccountService;
        private final MovementService movementService;
        private final PaymentService paymentService;
        public DatabaseManagerBuilder(SessionFactory databaseSession) {
            final ApprovalService approvalService = new ApprovalService(new ApprovalDAO(databaseSession));
            final BankAccountHolderService holderService = new BankAccountHolderService(new BankAccountHolderDAO(databaseSession));
            this.bankAccountService = new BankAccountService(
                    new BankAccountDAO(databaseSession),
                    holderService
            );
            this.movementService = new MovementService(
                    new MovementDAO(databaseSession),
                    bankAccountService
            );
            this.paymentService = new PaymentService(
                    new PaymentDAO(databaseSession),
                    approvalService,
                    movementService,
                    holderService,
                    bankAccountService
            );
        }

        public DatabaseState build() {
            return new DatabaseState(this);
        }

    }

    private enum RequestType { CREATE_ACCOUNT, DELETE_ACCOUNT, BALANCE, GET_MOVEMENTS, ADD_EXPENSE, ORDER_PAYMENT }
    private final BankAccountService bankAccountService;
    private final MovementService movementService;
    private final PaymentService paymentService;
    private final Map<RequestType, Set<OffsetDateTime>> timestamps = new HashMap<>();

    private DatabaseState(DatabaseManagerBuilder builder) {
        this.bankAccountService = builder.bankAccountService;
        this.movementService = builder.movementService;
        this.paymentService = builder.paymentService;
        for (RequestType type : RequestType.values())
            timestamps.put(type, new HashSet<>());
    }

    private Set<OffsetDateTime> getTimestamps(RequestType type) {
        return this.timestamps.get(type);
    }

    private void addTimestamp(RequestType type, OffsetDateTime timestamp) {
        this.timestamps.get(type).add(timestamp);
    }

    private boolean oldTimestampString(RequestType type, OffsetDateTime timestamp) {
        return getTimestamps(type).contains(timestamp);
    }

    private void beforeAll(RequestType type, OffsetDateTime timestamp) {
        if (oldTimestampString(type, timestamp)) throw new ReplayAttackException();
        addTimestamp(type, timestamp);
    }

    @Override
    public void createAccount(List<String> usernames, byte[] password, BigDecimal initialDeposit, OffsetDateTime timestamp) {
        beforeAll(RequestType.CREATE_ACCOUNT, timestamp);
        BankAccountDto ignore = bankAccountService.createAccount(usernames, password, initialDeposit);
    }

    @Override
    public void deleteAccount(String username, byte[] password, OffsetDateTime timestamp) {
        beforeAll(RequestType.DELETE_ACCOUNT, timestamp);
        if (bankAccountService.passwordCheck(username, password)) throw new WrongPasswordException();
        bankAccountService.deleteAccount(username);
    }

    @Override
    public BigDecimal balance(String username, byte[] password, OffsetDateTime timestamp) {
        beforeAll(RequestType.BALANCE, timestamp);
        if (bankAccountService.passwordCheck(username, password)) throw new WrongPasswordException();
        return bankAccountService.getBalance(username);
    }

    @Override
    public JsonArrayBuilder getMovements(String username, byte[] password, OffsetDateTime timestamp) {
        beforeAll(RequestType.GET_MOVEMENTS, timestamp);
        if (bankAccountService.passwordCheck(username, password)) throw new WrongPasswordException();
        JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();

        for (MovementDto movementDto : movementService.getAccountMovements(username)) {
            arrayBuilder.add(
                    Json.createObjectBuilder()
                    .add("currency", movementDto.currency())
                    .add("date", movementDto.date().toString())
                    .add("value", movementDto.amount().toString())
                    .add("description", movementDto.description())
            );
        }

        return arrayBuilder;
    }

    @Override
    public void addExpense(String username, byte[] password, LocalDateTime date, BigDecimal amount, String description, OffsetDateTime timestamp) {
        beforeAll(RequestType.ADD_EXPENSE, timestamp);
        if (bankAccountService.passwordCheck(username, password)) throw new WrongPasswordException();
        MovementDto ignored = movementService.addMovement(username, date, amount, description);
    }

    @Override
    public void orderPayment(String username, byte[] password, LocalDateTime date, BigDecimal amount, String description, String recipient, OffsetDateTime timestamp) {
        beforeAll(RequestType.ORDER_PAYMENT, timestamp);
        if (bankAccountService.passwordCheck(username, password)) throw new WrongPasswordException();
        PaymentDto ignored = paymentService.orderPayment(username, date, amount, description, recipient);
    }
    
}
