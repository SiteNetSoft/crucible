import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * What a small service spends its time on: parsing JSON text into a tree of nodes, walking the
 * tree through an interface, grouping values in hash maps, and writing JSON back out. Allocation,
 * strings, collections and virtual calls, rather than arithmetic over arrays.
 *
 *   JsonBench <rounds>
 *
 * Prints a checksum, so that differently compiled images can be compared for equal results.
 */
public class JsonBench {

    interface Node {
        void write(StringBuilder out);

        long weigh(Visitor visitor);
    }

    interface Visitor {
        long number(double value);

        long text(String value);

        long key(String name);
    }

    record Num(double value) implements Node {
        public void write(StringBuilder out) {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                out.append((long) value);
            } else {
                out.append(value);
            }
        }

        public long weigh(Visitor visitor) {
            return visitor.number(value);
        }
    }

    record Str(String value) implements Node {
        public void write(StringBuilder out) {
            quote(out, value);
        }

        public long weigh(Visitor visitor) {
            return visitor.text(value);
        }
    }

    record Bool(boolean value) implements Node {
        public void write(StringBuilder out) {
            out.append(value);
        }

        public long weigh(Visitor visitor) {
            return value ? 1 : 0;
        }
    }

    static final class Nil implements Node {
        static final Nil INSTANCE = new Nil();

        public void write(StringBuilder out) {
            out.append("null");
        }

        public long weigh(Visitor visitor) {
            return 0;
        }
    }

    record Arr(List<Node> items) implements Node {
        public void write(StringBuilder out) {
            out.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                items.get(i).write(out);
            }
            out.append(']');
        }

        public long weigh(Visitor visitor) {
            long sum = 0;
            for (Node item : items) {
                sum += item.weigh(visitor);
            }
            return sum;
        }
    }

    record Obj(Map<String, Node> fields) implements Node {
        public void write(StringBuilder out) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Node> field : fields.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(out, field.getKey());
                out.append(':');
                field.getValue().write(out);
            }
            out.append('}');
        }

        public long weigh(Visitor visitor) {
            long sum = 0;
            for (Map.Entry<String, Node> field : fields.entrySet()) {
                sum += visitor.key(field.getKey()) + field.getValue().weigh(visitor);
            }
            return sum;
        }
    }

    static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Node parse() {
            Node node = value();
            space();
            if (at != text.length()) {
                throw error("trailing text");
            }
            return node;
        }

        private IllegalArgumentException error(String what) {
            return new IllegalArgumentException(what + " at " + at);
        }

        private void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
                    return;
                }
                at++;
            }
        }

        private Node value() {
            space();
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return new Str(string());
                case 't':
                    expect("true");
                    return new Bool(true);
                case 'f':
                    expect("false");
                    return new Bool(false);
                case 'n':
                    expect("null");
                    return Nil.INSTANCE;
                default:
                    return number();
            }
        }

        private void expect(String word) {
            if (!text.startsWith(word, at)) {
                throw error("expected " + word);
            }
            at += word.length();
        }

        private Node object() {
            at++;
            Map<String, Node> fields = new LinkedHashMap<>();
            space();
            if (text.charAt(at) == '}') {
                at++;
                return new Obj(fields);
            }
            while (true) {
                space();
                String name = string();
                space();
                if (text.charAt(at++) != ':') {
                    throw error("expected :");
                }
                fields.put(name, value());
                space();
                char c = text.charAt(at++);
                if (c == '}') {
                    return new Obj(fields);
                }
                if (c != ',') {
                    throw error("expected , or }");
                }
            }
        }

        private Node array() {
            at++;
            List<Node> items = new ArrayList<>();
            space();
            if (text.charAt(at) == ']') {
                at++;
                return new Arr(items);
            }
            while (true) {
                items.add(value());
                space();
                char c = text.charAt(at++);
                if (c == ']') {
                    return new Arr(items);
                }
                if (c != ',') {
                    throw error("expected , or ]");
                }
            }
        }

        private String string() {
            if (text.charAt(at) != '"') {
                throw error("expected string");
            }
            int start = ++at;
            while (text.charAt(at) != '"' && text.charAt(at) != '\\') {
                at++;
            }
            if (text.charAt(at) == '"') {
                return text.substring(start, at++);
            }
            StringBuilder sb = new StringBuilder(text.substring(start, at));
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        private Node number() {
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                if ((c < '0' || c > '9') && c != '-' && c != '+' && c != '.' && c != 'e' && c != 'E') {
                    break;
                }
                at++;
            }
            if (start == at) {
                throw error("unexpected character");
            }
            return new Num(Double.parseDouble(text.substring(start, at)));
        }
    }

    /** Counts how often each key and each short string turns up, the way a report would. */
    static final class Tally implements Visitor {
        final Map<String, Integer> keys = new HashMap<>();
        final Map<String, Integer> words = new HashMap<>();

        public long number(double value) {
            return (long) value;
        }

        public long text(String value) {
            if (value.length() < 12) {
                words.merge(value, 1, Integer::sum);
            }
            return value.length();
        }

        public long key(String name) {
            keys.merge(name, 1, Integer::sum);
            return name.hashCode() & 0xff;
        }
    }

    static String document(int seed, int orders) {
        String[] cities = {"Lyon", "Porto", "Gdansk", "Turku", "Graz", "Cork", "Bergen"};
        String[] states = {"new", "paid", "shipped", "returned"};
        StringBuilder sb = new StringBuilder();
        sb.append("{\"batch\": ").append(seed).append(", \"source\": \"warehouse-").append(seed % 5).append("\", \"orders\": [");
        long x = seed * 2654435761L + 1;
        for (int i = 0; i < orders; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            int r = (int) (x >>> 33);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{\"id\": ").append(1000 + i);
            sb.append(", \"customer\": {\"name\": \"customer \\\"").append(r % 97).append("\\\"\", \"city\": \"").append(cities[r % cities.length]).append("\"}");
            sb.append(", \"state\": \"").append(states[(r >>> 3) % states.length]).append('"');
            sb.append(", \"express\": ").append((r & 8) != 0);
            sb.append(", \"note\": ").append((r & 48) == 0 ? "null" : "\"line one\\nline two\"");
            sb.append(", \"items\": [");
            int items = 1 + (r >>> 5) % 5;
            for (int j = 0; j < items; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append("{\"sku\": \"SKU-").append((r >>> (j + 2)) % 500).append("\", \"qty\": ").append(1 + (r >>> j) % 4);
                sb.append(", \"price\": ").append(((r >>> (j + 7)) % 20000) / 100.0).append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        String[] documents = new String[8];
        for (int i = 0; i < documents.length; i++) {
            documents[i] = document(i + 1, 400);
        }
        long checksum = 0;
        for (int r = 0; r < rounds; r++) {
            String text = documents[r % documents.length];
            Node tree = new Parser(text).parse();
            Tally tally = new Tally();
            checksum += tree.weigh(tally);
            checksum += tally.keys.size() * 31L + tally.words.size();
            StringBuilder out = new StringBuilder(text.length());
            tree.write(out);
            Node again = new Parser(out.toString()).parse();
            checksum ^= again.weigh(new Tally()) + out.length();
        }
        System.out.println(checksum);
    }
}
