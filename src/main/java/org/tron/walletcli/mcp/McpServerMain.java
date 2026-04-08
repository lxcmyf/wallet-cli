package org.tron.walletcli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.commons.lang3.tuple.Pair;
import org.tron.common.crypto.Sha256Sm3Hash;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.CommandHelpUtil;
import org.tron.common.utils.TransactionUtils;
import org.tron.common.utils.Utils;
import org.tron.ledger.LedgerSignUtil;
import org.tron.ledger.listener.TransactionSignManager;
import org.tron.ledger.sdk.LedgerConstant;
import org.tron.trident.core.exceptions.IllegalException;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;
import org.tron.walletcli.Client;
import org.tron.walletserver.ApiClient;
import org.tron.walletserver.WalletApi;

public final class McpServerMain {

  private McpServerMain() {
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("mcp.mode", "true");

    OutputStream protocolOut = System.out;
    InputStream protocolIn = System.in;
    System.setOut(new PrintStream(System.err, true, "UTF-8"));

    ObjectMapper mapper = new ObjectMapper();
    StdioServerTransportProvider transport = new StdioServerTransportProvider(
        mapper,
        protocolIn,
        protocolOut
    );

    McpSyncServer server = McpServer.sync(transport)
        .serverInfo("wallet-cli-mcp", Utils.VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder()
            .tools(true)
            .build())
        .build();

    registerTools(server);

    new CountDownLatch(1).await();
  }

  private static void registerTools(McpSyncServer server) {
    server.addTool(new McpServerFeatures.SyncToolSpecification(
        tool("wallet_list_commands",
            "List all supported wallet-cli commands.",
            schemaNoArgs()),
        (exchange, arguments) -> new McpSchema.CallToolResult(listCommands(), false)
    ));

    server.addTool(new McpServerFeatures.SyncToolSpecification(
        tool("wallet_help",
            "Get help for a specific command.",
            schemaSingleString("command", "Command name, e.g. TransferUSDT")),
        (exchange, arguments) -> help(arguments)
    ));

    server.addTool(new McpServerFeatures.SyncToolSpecification(
        tool("wallet_get_latest_block",
            "Get the latest block from the connected fullnode.",
            schemaNoArgs()),
        (exchange, arguments) -> getLatestBlock()
    ));

    server.addTool(new McpServerFeatures.SyncToolSpecification(
        tool("wallet_get_balance",
            "Get TRX balance for a given address.",
            schemaSingleString("address", "Base58 TRON address")),
        (exchange, arguments) -> getBalance(arguments)
    ));

    server.addTool(new McpServerFeatures.SyncToolSpecification(
        tool("wallet_get_account",
            "Get account information for a given address.",
            schemaSingleString("address", "Base58 TRON address")),
        (exchange, arguments) -> getAccount(arguments)
    ));

    server.addTool(new McpServerFeatures.SyncToolSpecification(
        tool("wallet_sendcoin",
            "Transfer TRX (send TRX) by signing and broadcasting a transfer transaction.",
            schemaSendCoin()),
        (exchange, arguments) -> sendCoin(arguments)
    ));
  }

  private static McpSchema.Tool tool(String name, String description, String inputSchema) {
    return McpSchema.Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(inputSchema)
        .build();
  }

  private static String schemaNoArgs() {
    return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
  }

  private static String schemaSingleString(String name, String description) {
    return "{\"type\":\"object\",\"properties\":{\"" + name + "\":{\"type\":\"string\",\"description\":\""
        + description + "\"}},\"required\":[\"" + name + "\"],\"additionalProperties\":false}";
  }

  private static String schemaSendCoin() {
    return "{\"type\":\"object\","
        + "\"properties\":{"
        + "\"ownerAddress\":{\"type\":\"string\",\"description\":\"Sender Base58 TRON address (Ledger account)\"},"
        + "\"toAddress\":{\"type\":\"string\",\"description\":\"Receiver Base58 TRON address\"},"
        + "\"amountTrx\":{\"description\":\"Amount in TRX, e.g. 1 or 0.5\"},"
        + "\"ledgerPath\":{\"type\":\"string\",\"description\":\"Ledger derivation path, default m/44'/195'/0'/0/0\"},"
        + "\"permissionId\":{\"type\":\"integer\",\"description\":\"Optional permission id, default 0\"}"
        + "},"
        + "\"required\":[\"ownerAddress\",\"toAddress\",\"amountTrx\"],"
        + "\"additionalProperties\":false}";
  }

  private static String listCommands() {
    String[] commands = Client.getCommandList();
    List<String> list = Arrays.asList(commands);
    return String.join("\n", list);
  }

  private static McpSchema.CallToolResult help(Map<String, Object> arguments) {
    String command = getString(arguments, "command");
    if (command == null || command.isEmpty()) {
      return new McpSchema.CallToolResult("Missing parameter: command", true);
    }
    String help = CommandHelpUtil.getCommandHelp(command.toLowerCase());
    return new McpSchema.CallToolResult(help, false);
  }

  private static McpSchema.CallToolResult getLatestBlock() {
    try {
      Response.BlockExtention block = WalletApi.getBlock2(-1);
      if (block == null || block == Response.BlockExtention.getDefaultInstance()) {
        return new McpSchema.CallToolResult("No block data available", true);
      }
      String output = Utils.printBlockExtention(block);
      return new McpSchema.CallToolResult(output, false);
    } catch (IllegalException | InvalidProtocolBufferException e) {
      return new McpSchema.CallToolResult("Failed to get latest block: " + e.getMessage(), true);
    }
  }

  private static McpSchema.CallToolResult getBalance(Map<String, Object> arguments) {
    String address = getString(arguments, "address");
    if (address == null || address.isEmpty()) {
      return new McpSchema.CallToolResult("Missing parameter: address", true);
    }
    byte[] addressBytes = WalletApi.decodeFromBase58Check(address);
    if (addressBytes == null) {
      return new McpSchema.CallToolResult("Invalid address", true);
    }
    Response.Account account = WalletApi.queryAccount(addressBytes);
    if (account == null) {
      return new McpSchema.CallToolResult("Account not found", true);
    }
    long balance = account.getBalance();
    BigDecimal trx = BigDecimal.valueOf(balance)
        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.DOWN);
    String output = "Balance = " + balance + " SUN = " + trx.toPlainString() + " TRX";
    return new McpSchema.CallToolResult(output, false);
  }

  private static McpSchema.CallToolResult getAccount(Map<String, Object> arguments) {
    String address = getString(arguments, "address");
    if (address == null || address.isEmpty()) {
      return new McpSchema.CallToolResult("Missing parameter: address", true);
    }
    byte[] addressBytes = WalletApi.decodeFromBase58Check(address);
    if (addressBytes == null) {
      return new McpSchema.CallToolResult("Invalid address", true);
    }
    Response.Account account = WalletApi.queryAccount(addressBytes);
    if (account == null) {
      return new McpSchema.CallToolResult("Account not found", true);
    }
    String output = Utils.formatMessageString(account);
    return new McpSchema.CallToolResult(output, false);
  }

  private static McpSchema.CallToolResult sendCoin(Map<String, Object> arguments) {
    String ownerAddressText = getString(arguments, "ownerAddress");
    String toAddressText = getString(arguments, "toAddress");
    String ledgerPath = getString(arguments, "ledgerPath");
    BigDecimal amountTrx = getDecimal(arguments, "amountTrx");
    Integer permissionId = getInteger(arguments, "permissionId");

    if (ownerAddressText == null || ownerAddressText.isEmpty()) {
      return new McpSchema.CallToolResult("Missing parameter: ownerAddress", true);
    }
    if (toAddressText == null || toAddressText.isEmpty()) {
      return new McpSchema.CallToolResult("Missing parameter: toAddress", true);
    }
    if (amountTrx == null) {
      return new McpSchema.CallToolResult("Missing or invalid parameter: amountTrx", true);
    }
    if (amountTrx.compareTo(BigDecimal.ZERO) <= 0) {
      return new McpSchema.CallToolResult("amountTrx must be greater than 0", true);
    }
    BigDecimal amountSunDecimal = amountTrx.movePointRight(6);
    if (amountSunDecimal.stripTrailingZeros().scale() > 0) {
      return new McpSchema.CallToolResult("amountTrx supports up to 6 decimals", true);
    }

    long amountSun;
    try {
      amountSun = amountSunDecimal.longValueExact();
    } catch (ArithmeticException e) {
      return new McpSchema.CallToolResult("amountTrx is out of range", true);
    }

    byte[] toAddress = WalletApi.decodeFromBase58Check(toAddressText);
    if (toAddress == null) {
      return new McpSchema.CallToolResult("Invalid toAddress", true);
    }
    byte[] ownerAddress = WalletApi.decodeFromBase58Check(ownerAddressText);
    if (ownerAddress == null) {
      return new McpSchema.CallToolResult("Invalid ownerAddress", true);
    }
    if (Arrays.equals(ownerAddress, toAddress)) {
      return new McpSchema.CallToolResult("Cannot transfer TRX to yourself", true);
    }
    if (permissionId != null && permissionId < 0) {
      return new McpSchema.CallToolResult("permissionId must be >= 0", true);
    }
    if (ledgerPath == null || ledgerPath.isEmpty()) {
      ledgerPath = LedgerConstant.DEFAULT_PATH;
    }

    ApiClient apiClient = null;
    try {
      apiClient = createApiClient();
      Response.TransactionExtention txExt = apiClient.transfer(ownerAddress, toAddress, amountSun);
      if (txExt == null) {
        return new McpSchema.CallToolResult("Failed to create transfer transaction", true);
      }
      Response.TransactionReturn ret = txExt.getResult();
      if (ret == null || !ret.getResult()) {
        String code = ret == null ? "UNKNOWN" : String.valueOf(ret.getCode());
        String message = (ret == null || ret.getMessage() == null) ? "" : ret.getMessage().toStringUtf8();
        return new McpSchema.CallToolResult("Transfer create failed: code=" + code + ", message=" + message, true);
      }

      Chain.Transaction transaction = txExt.getTransaction();
      if (transaction == null || transaction.getRawData().getContractCount() == 0) {
        return new McpSchema.CallToolResult("Transaction is empty", true);
      }

      transaction = TransactionUtils.setTimestamp(transaction);
      transaction = TransactionUtils.setExpirationTime(transaction, false);
      transaction = setPermissionId(transaction, permissionId == null ? 0 : permissionId);

      boolean signed = LedgerSignUtil.requestLedgerSignLogic(
          transaction,
          ledgerPath,
          ownerAddressText,
          false
      );
      if (!signed) {
        return new McpSchema.CallToolResult(
            "Ledger sign failed or was rejected. Please check Ledger connection, Tron app, and device confirmation.",
            true
        );
      }

      Chain.Transaction signedTransaction = TransactionSignManager.getInstance().getTransaction();
      if (signedTransaction == null || signedTransaction.getSignatureCount() == 0) {
        return new McpSchema.CallToolResult("Ledger returned empty signature", true);
      }

      String txId = ByteArray.toHexString(Sha256Sm3Hash.hash(signedTransaction.getRawData().toByteArray()));
      Pair<Boolean, String> broadcast = apiClient.broadcastTransactionWithMessage(signedTransaction);
      if (!broadcast.getLeft()) {
        // Some nodes may return an error even though the tx is already accepted (e.g. duplicate submit).
        if (isTransactionExists(apiClient, txId)) {
          String accepted = "Broadcast returned error but tx already exists, txId=" + txId
              + ", message=" + broadcast.getRight();
          return new McpSchema.CallToolResult(accepted, false);
        }
        return new McpSchema.CallToolResult(
            "Broadcast failed, txId=" + txId + ", message=" + broadcast.getRight(),
            true
        );
      }

      String output = "Broadcast success, txId=" + txId
          + ", from=" + ownerAddressText
          + ", to=" + toAddressText
          + ", amount=" + amountSun + " SUN (" + amountTrx.stripTrailingZeros().toPlainString() + " TRX)";
      return new McpSchema.CallToolResult(output, false);
    } catch (IllegalException e) {
      return new McpSchema.CallToolResult("Failed to send TRX: " + e.getMessage(), true);
    } catch (Exception e) {
      return new McpSchema.CallToolResult("Failed to send TRX: " + e.getMessage(), true);
    } finally {
      TransactionSignManager.getInstance().setTransaction(null);
      if (apiClient != null) {
        apiClient.close();
      }
    }
  }

  private static ApiClient createApiClient() {
    Pair<Pair<String, Boolean>, Pair<String, Boolean>> customNodes = WalletApi.getCustomNodes();
    if (customNodes == null || customNodes.getLeft() == null || customNodes.getRight() == null) {
      throw new IllegalStateException("RPC nodes are not configured");
    }
    return new ApiClient(
        customNodes.getLeft().getLeft(),
        customNodes.getRight().getLeft(),
        customNodes.getLeft().getRight(),
        customNodes.getRight().getRight()
    );
  }

  private static String getString(Map<String, Object> arguments, String key) {
    if (arguments == null) {
      return null;
    }
    Object value = arguments.get(key);
    if (value == null) {
      return null;
    }
    return String.valueOf(value).trim();
  }

  private static BigDecimal getDecimal(Map<String, Object> arguments, String key) {
    String value = getString(arguments, key);
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer getInteger(Map<String, Object> arguments, String key) {
    String value = getString(arguments, key);
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Chain.Transaction setPermissionId(Chain.Transaction transaction, int permissionId) {
    if (permissionId <= 0 || transaction.getRawData().getContractCount() == 0) {
      return transaction;
    }
    Chain.Transaction.raw.Builder raw = transaction.getRawData().toBuilder();
    Chain.Transaction.Contract.Builder contract = raw.getContract(0).toBuilder()
        .setPermissionId(permissionId);
    raw.clearContract();
    raw.addContract(contract);
    return transaction.toBuilder().setRawData(raw.build()).build();
  }

  private static boolean isTransactionExists(ApiClient apiClient, String txId) {
    try {
      Chain.Transaction tx = apiClient.getTransactionById(txId);
      return tx != null && tx.getRawData().getContractCount() > 0;
    } catch (Exception e) {
      return false;
    }
  }
}
