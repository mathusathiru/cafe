package helpers;

public class RegexProcessor {

    // input acceptance handled through regex string matching, to match check quickly rather than iterate through characters
    private static final String COLLECT_PATTERN = "^collect$";
    private static final String EXIT_PATTERN = "^exit$";
    private static final String STATUS_PATTERN = "^order status$";

    private static final String QUANTITY = "(\\d+)\\s+";
    private static final String ITEM = "(tea|coffee)(s)?";
    private static final String AND_CONNECTOR = "\\s+and\\s+";
    private static final String ORDER_START = "^order\\s+";

    private static final String ORDER_PATTERN = ORDER_START +
            QUANTITY +
            ITEM +
            "(" +
            AND_CONNECTOR +
            QUANTITY +
            ITEM +
            ")?$";

    public static String identifyCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "invalid";
        }

        if (command.matches(COLLECT_PATTERN)) return "collect";
        if (command.matches(EXIT_PATTERN)) return "exit";
        if (command.matches(STATUS_PATTERN)) return "status";
        if (command.matches(ORDER_PATTERN)) return "order";

        return "invalid";
    }

    public static int[] parseOrder(String command) {
        String[] parts = command.substring(6).split(" and ");
        int totalTeas = 0;
        int totalCoffees = 0;

        for (String part : parts) {
            String[] words = part.trim().split("\\s+");
            if (words.length < 2) {
                return new int[]{0, 0};
            }

            int quantity;
            try {
                quantity = Integer.parseInt(words[0]);
                if (quantity <= 0) {
                    return new int[]{0, 0};
                }
            } catch (NumberFormatException e) {
                return new int[]{0, 0};
            }

            String item = words[1].toLowerCase();
            boolean isPlural = item.endsWith("s");
            String itemSingular = isPlural ? item.substring(0, item.length() - 1) : item;
            boolean invalidGrammar = (quantity == 1 && isPlural) || (quantity > 1 && !isPlural);

            if (itemSingular.equals("tea")) {
                if (invalidGrammar) {
                    return new int[]{0, 0};
                }
                totalTeas += quantity;
            } else if (itemSingular.equals("coffee")) {
                if (invalidGrammar) {
                    return new int[]{0, 0};
                }
                totalCoffees += quantity;
            } else {
                return new int[]{0, 0};
            }
        }

        return new int[]{totalTeas, totalCoffees};
    }
}