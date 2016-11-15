import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AnswersUtils {
    private Connection connection;
    private String pollId;
    private String ip;

    public AnswersUtils(Connection connection, String pollId, String ip) {
        this.connection = connection;
        this.pollId = pollId;
        this.ip = ip;
    }

    public void enterAnswersIntoDatabase(JsonArray answers) {
        // Go through head's adding changed vote values (for first run all answers given)
        for (int i = 0; i < answers.size(); i++) {
            JsonArray headAnswers = answers.get(i).getAsJsonArray();

            // Go through a single head
            for (int j = 0; j < headAnswers.size(); j++) {
                JsonElement elem = headAnswers.get(j);
                JsonObject answer = elem.getAsJsonObject();

                boolean vote;
                try {
                    vote = answer.get("support").getAsString().equals("yes");
                } catch (Exception e) {
                    // In all other cases than the first "support" is not a
                    // field so we don't update it with anything
                    continue;
                }

                Integer id = answer.get("id").getAsInt();

                try {
                    insertAnswer(vote, id);
                } catch (SQLException e) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    public Object resolveDynamicQuestions(JsonObject data) {
        // Pull from the database into argument objects
        try {
            ResultSet rs = getAnswers();
            rs.next();
            // rs is now the first row from the answers table with user_id (should be unique)

            Integer nextLevel = data.get("nextLevel").getAsInt();

            List<List<Box>> dynamicQuestions = new ArrayList<>();

            do {
                // 1st elem of each inner list is the head
                ResultSet headIds = getHeadIds(nextLevel);

                // This case will occur when we go past last level of tree
                if (!headIds.isBeforeFirst()) {
                    return "STOP";
                }

                // For each head find its inconsistencies and store it in a
                // list of boxes
                while (headIds.next()) {
                    Integer currentHead = headIds.getInt("statement_id");
                    ResultSet children = getChildren(currentHead);
                    dynamicQuestions = gatherDynamicQs(rs, dynamicQuestions, children);
                }

                nextLevel++;
            } while (dynamicQuestions.isEmpty());

            return new DynamicData(dynamicQuestions, nextLevel);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return "500 ERROR";
        }
    }

    public void addUser() {
        try {
            PreparedStatement createUser = connection.prepareStatement("INSERT INTO ? (user_id)");
            createUser.setString(1, pollId + "_answers");
            PreparedStatement insertIp = connection.prepareStatement(createUser.toString().replace("'", "\"") + "  VALUES(?);");

            insertIp.setString(1, ip);
            insertIp.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    private void insertAnswer(boolean vote, Integer id) throws SQLException {
        PreparedStatement insertAnswer = connection.prepareStatement("UPDATE ? SET ?=");
        insertAnswer.setString(1, pollId + "_answers");
        insertAnswer.setString(2, id.toString());

        PreparedStatement insertValues = connection.prepareStatement(insertAnswer.toString().replace("'", "\"")
                + "? WHERE user_id=?;");

        insertValues.setBoolean(1, vote);
        insertValues.setString(2, ip);

        insertValues.executeUpdate();
    }

    private ResultSet getAnswers() throws SQLException {
        // Get row of answers for user
        PreparedStatement getUserAnswers = connection.prepareStatement("SELECT * FROM ? WHERE user_id=");
        getUserAnswers.setString(1, pollId + "_answers");
        PreparedStatement getAnswers = connection.prepareStatement(getUserAnswers.toString().replace("'", "\"") + "?;");
        getAnswers.setString(1, ip);

        // Get all answers
        return getAnswers.executeQuery();
    }

    private ResultSet getChildren(Integer currentHead) throws SQLException {
        // Get parent = currentHead and children rows in poll
        // table where parent_id = currentHead
        PreparedStatement getValues = connection.prepareStatement("SELECT * FROM ? WHERE parent_id=");
        getValues.setString(1, pollId);
        PreparedStatement getChildren = connection.prepareStatement(getValues.toString().replace("'", "\"") + "? OR" +
                " statement_id=? ORDER BY statement_id");
        getChildren.setInt(1, currentHead);
        getChildren.setInt(2, currentHead);

        return getChildren.executeQuery();
    }

    private ResultSet getHeadIds(Integer nextLevel) throws SQLException {
        // Get all ids for a level
        PreparedStatement getHeads = connection.prepareStatement("SELECT \"statement_id\" FROM ? WHERE \"level\"=");
        getHeads.setString(1, pollId);
        PreparedStatement getHeadIds = connection.prepareStatement(getHeads.toString().replace("'","\"") + nextLevel);
        return getHeadIds.executeQuery();
    }

    private List<List<Box>> gatherDynamicQs(
            ResultSet rs,
            List<List<Box>> dynamicQuestions,
            ResultSet children) throws SQLException {
        if (children.isBeforeFirst()) {
            // Only true if there are children (ignore heads without children)
            children.next(); // Head is the first here as it has the lowest index

            Argument head = new Argument(
                    rs.getBoolean("0"),
                    children.getString("statement"),
                    children.getString("type").equals("Pro"));
            head.setId(children.getInt("statement_id"));

            // While there is a row for a child
            while (children.next()) {
                addChild(rs, children, head);
            }

            // Set of all rows for relevant nodes in tree
            List<Argument> inconsistencies = head.getInconsistencies();

            // If there are inconsistencies then store them with
            // their head node
            if (!inconsistencies.isEmpty()) {
                List<Box> headInconsistencies = new ArrayList<>();
                headInconsistencies.add(0, head.toBox());
                headInconsistencies
                        .addAll(inconsistencies
                            .stream()
                            .map(Argument::toBox)
                            .collect(Collectors.toList()));

                dynamicQuestions.add(headInconsistencies);
            }
        }

        return dynamicQuestions;
    }

    private void addChild(ResultSet rs, ResultSet children, Argument head) throws SQLException {
        Integer argumentId = children.getInt("statement_id");
        Integer parentId = children.getInt("parent_id");
        Argument arg = new Argument(
                rs.getBoolean(argumentId.toString()),
                children.getString("statement"),
                children.getString("type").equals("Pro"));
        arg.setId(argumentId);
        arg.setParent(parentId);

        head.addChild(arg);
    }
}