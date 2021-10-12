package in.ashwanthkumar.gocd.slack;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.models.BodyType;
import com.microsoft.graph.models.ChatMessage;
import com.microsoft.graph.models.ItemBody;
import com.microsoft.graph.requests.GraphServiceClient;
import com.thoughtworks.go.plugin.api.logging.Logger;
import in.ashwanthkumar.gocd.slack.jsonapi.Stage;
import in.ashwanthkumar.gocd.slack.ruleset.PipelineRule;
import in.ashwanthkumar.gocd.slack.ruleset.PipelineStatus;
import in.ashwanthkumar.gocd.slack.ruleset.Rules;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;

public class TeamsPipelineListener extends PipelineListener {
    private static final Logger LOG = Logger.getLoggerFor(TeamsPipelineListener.class);
    private final GraphServiceClient<?> client;

    public TeamsPipelineListener(Rules rules) {
        super(rules);
        client = GraphServiceClient.builder()
                .authenticationProvider(tokenAuthProvider(rules.getWebHookUrl()))
                .buildClient();
        LOG.info("Teams User: " + client.me().buildRequest().get().userPrincipalName);
    }

    private void sendMessage(PipelineRule rule, GoNotificationMessage message, PipelineStatus status) throws GoNotificationMessage.BuildDetailsNotFoundException, URISyntaxException, IOException {
        final String teamId = getTeamId(rule);
        final String channelId = getChannelId(rule, teamId);

        var details = message.fetchDetails(rules);
        Stage stage = message.pickCurrentStage(details.stages);

        ChatMessage msg = new ChatMessage();
        msg.subject = String.format("Stage [%s] %s %s",
                        message.fullyQualifiedJobName(),
                        status.verb(),
                        status)
                .replaceAll("\\s+", " ");
        ItemBody body = new ItemBody();
        msg.body = body;
        body.contentType = BodyType.HTML;
        StringBuilder sb = new StringBuilder();

        sb.append("<a href=\"")
                .append(message.goServerUrl(rules.getGoServerHost()))
                .append("\">details</a>")
                .append("<p>Triggered by: ").append(stage.approvedBy).append("</p>")
                .append("<p>Reason: ").append(details.buildCause.triggerMessage).append("</p>");

        body.content = sb.toString();

        client.teams(teamId)
                .channels(channelId)
                .messages()
                .buildRequest()
                .post(msg);
    }

    private IAuthenticationProvider tokenAuthProvider(String rawToken) {
        AccessToken accessToken = new AccessToken(rawToken, OffsetDateTime.MAX);
        TokenCredential tokenCredential = tokenRequestContext -> Mono.just(accessToken);
        return new TokenCredentialAuthProvider(tokenCredential);
    }

    private String getTeamId(PipelineRule rule) {
        final String teamId = rule.getTeam();
        LOG.info("Using Team ID: " + teamId);
        return teamId;
    }

    private String getChannelId(PipelineRule rule, String teamId) {
        final String channelSetting = rule.getChannel();
        if (channelSetting.contains(":")) {
            LOG.info("The configured channel was detected as an ID: " + channelSetting);
            return channelSetting;
        }
        var request = client.teams(teamId).channels().buildRequest().get();
        if (request == null) {
            throw new RuntimeException("Error finding team: " + teamId);
        }
        String channelId = request.getCurrentPage()
                .stream()
                .filter(channel -> channel.displayName != null
                        && channel.displayName.equals(channelSetting))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Channel not found: " + channelSetting))
                .id;
        if (channelId == null) {
            throw new RuntimeException("Channel ID not found for: " + channelSetting);
        }
        LOG.info("Using Channel ID: " + channelId);
        return channelId;
    }

    @Override
    public void onBuilding(PipelineRule rule, GoNotificationMessage message) throws Exception {
        sendMessage(rule, message, PipelineStatus.BUILDING);
    }

    @Override
    public void onPassed(PipelineRule rule, GoNotificationMessage message) throws Exception {
        sendMessage(rule, message, PipelineStatus.PASSED);
    }

    @Override
    public void onFailed(PipelineRule rule, GoNotificationMessage message) throws Exception {
        sendMessage(rule, message, PipelineStatus.FAILED);
    }

    @Override
    public void onBroken(PipelineRule rule, GoNotificationMessage message) throws Exception {
        sendMessage(rule, message, PipelineStatus.BROKEN);
    }

    @Override
    public void onFixed(PipelineRule rule, GoNotificationMessage message) throws Exception {
        sendMessage(rule, message, PipelineStatus.FIXED);
    }

    @Override
    public void onCancelled(PipelineRule rule, GoNotificationMessage message) throws Exception {
        sendMessage(rule, message, PipelineStatus.CANCELLED);
    }
}
