package swipread.api.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Règles d'un robots.txt pour un robot (RFC 9309) : groupe du robot s'il existe, sinon groupe « * » ; la règle
 * Allow/Disallow la plus longue qui correspond l'emporte, Allow à longueur égale ; « * » et « $ » sont gérés.
 */
final class RobotsRules {

    static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());
    static final RobotsRules DISALLOW_ALL = new RobotsRules(List.of(new Rule(false, "/", Pattern.compile("/.*"))));

    private record Rule(boolean allow, String path, Pattern pattern) {
    }

    private final List<Rule> rules;

    private RobotsRules(List<Rule> rules) {
        this.rules = rules;
    }

    static RobotsRules parse(String robotsTxt, String robotName) {
        String robot = robotName.toLowerCase(Locale.ROOT);
        List<Rule> mine = new ArrayList<>();
        List<Rule> any = new ArrayList<>();
        boolean foundMine = false;
        boolean foundAny = false;
        List<String> agents = new ArrayList<>();
        boolean inRules = false;
        List<Rule> current = new ArrayList<>();
        for (String raw : robotsTxt.split("\\R")) {
            String line = raw.replaceFirst("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (inRules) {
                    // Nouveau groupe : on range le précédent
                    foundMine |= assign(agents, current, robot, mine, any);
                    foundAny |= agents.contains("*");
                    agents = new ArrayList<>();
                    current = new ArrayList<>();
                    inRules = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
            } else if (key.equals("allow") || key.equals("disallow")) {
                inRules = true;
                if (!value.isEmpty()) {
                    current.add(new Rule(key.equals("allow"), value, toPattern(value)));
                }
            }
        }
        foundMine |= assign(agents, current, robot, mine, any);
        foundAny |= agents.contains("*");
        return new RobotsRules(foundMine ? mine : foundAny ? any : List.of());
    }

    /** @return vrai si le chemin (avec sa requête) peut être lu */
    boolean allows(String pathAndQuery) {
        String path = pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.pattern().matcher(path).lookingAt()) {
                if (best == null || rule.path().length() > best.path().length()
                        || rule.path().length() == best.path().length() && rule.allow()) {
                    best = rule;
                }
            }
        }
        return best == null || best.allow();
    }

    private static boolean assign(List<String> agents, List<Rule> rules, String robot, List<Rule> mine, List<Rule> any) {
        boolean isMine = agents.stream().anyMatch(agent -> !agent.equals("*") && robot.equals(agent));
        if (isMine) {
            mine.addAll(rules);
        } else if (agents.contains("*")) {
            any.addAll(rules);
        }
        return isMine;
    }

    private static Pattern toPattern(String path) {
        boolean anchored = path.endsWith("$");
        String body = anchored ? path.substring(0, path.length() - 1) : path;
        StringBuilder regex = new StringBuilder();
        for (String part : body.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        if (anchored) {
            regex.append("$");
        }
        return Pattern.compile(regex.toString());
    }
}
