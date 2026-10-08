package com.example.commitgap.cli;

import com.example.commitgap.core.CommitGapVersion;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the {@code commitgap} command. */
@Command(name = "commitgap",
        mixinStandardHelpOptions = true,
        versionProvider = CommitGapCli.Version.class,
        description = {
                "Test what happens between commit and delivery.",
                "A local fault-injection lab for dual writes, transactional outbox, and idempotent consumers."},
        footer = {"",
                "Exit codes for run: 0 checks held, 1 violation measured, 2 invalid usage or measurement obstacle.",
                "Exit codes for demo and compare: 0 every expected result verified, 1 an expectation was not",
                "matched, 2 a measurement obstacle (inconclusive cell) or invalid usage."},
        subcommands = {
                DoctorCommand.class,
                DemoCommand.class,
                ScenariosCommand.class,
                RunCommand.class,
                CompareCommand.class,
                ReportCommand.class,
                CleanupCommand.class,
                CommandLine.HelpCommand.class})
public final class CommitGapCli implements Runnable {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int USAGE_OR_OBSTACLE = 2;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        System.exit(newCommandLine().execute(args));
    }

    static CommandLine newCommandLine() {
        // picocli already returns 2 (ExitCode.USAGE) for invalid input; failures during execution are also 2.
        CommandLine cmd = new CommandLine(new CommitGapCli());
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            commandLine.getErr().println("commitgap: " + Console.message(ex));
            return USAGE_OR_OBSTACLE;
        });
        return cmd;
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"commitgap " + CommitGapVersion.version(),
                    "report schema 1, scenario schema 1",
                    "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"};
        }
    }
}
