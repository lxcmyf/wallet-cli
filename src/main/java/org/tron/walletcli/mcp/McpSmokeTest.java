package org.tron.walletcli.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

public final class McpSmokeTest {

  private McpSmokeTest() {
  }

  public static void main(String[] args) {
    String classpath = "build/libs/wallet-cli.jar";
    if (args.length >= 1 && args[0] != null && !args[0].trim().isEmpty()) {
      classpath = args[0].trim();
    }

    ServerParameters serverParams = ServerParameters.builder("java")
        .args("-cp", classpath, "org.tron.walletcli.mcp.McpServerMain")
        .build();

    StdioClientTransport transport = new StdioClientTransport(serverParams);
    McpSyncClient client = McpClient.sync(transport)
        .clientInfo(new McpSchema.Implementation("wallet-cli-mcp-test", "1.0.0"))
        .build();

    try {
      client.initialize();
      McpSchema.ListToolsResult tools = client.listTools();
      System.out.println("Tools: " + tools);

      McpSchema.CallToolResult help = client.callTool(
          new McpSchema.CallToolRequest("wallet_help", Map.of("command", "GetBalance"))
      );
      System.out.println("Help(GetBalance): " + help);

      if (args.length >= 2 && args[1] != null && !args[1].trim().isEmpty()) {
        String address = args[1].trim();
        McpSchema.CallToolResult balance = client.callTool(
            new McpSchema.CallToolRequest("wallet_get_balance", Map.of("address", address))
        );
        System.out.println("Balance: " + balance);
      }
    } finally {
      client.closeGracefully();
    }
  }
}
