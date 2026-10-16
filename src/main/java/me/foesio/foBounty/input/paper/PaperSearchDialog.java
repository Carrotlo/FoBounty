package me.foesio.foBounty.input.paper;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.text.FoText;
import me.foesio.foBounty.config.SearchDialogConfig;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Loaded reflectively only on Paper with native dialogs. */
public final class PaperSearchDialog {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final ClickCallback.Options CALLBACK = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(5)).build();

    private PaperSearchDialog() {
    }

    public static boolean open(JavaPlugin plugin, Player player, SearchDialogConfig.Rendered request,
                               Consumer<String> onSubmit, Runnable onCancel) {
        String initial = request.initialValue();
        if (initial.length() > request.maxLength()) initial = initial.substring(0, request.maxLength());
        List<DialogBody> body = request.body().stream()
                .filter(line -> !line.isBlank())
                .map(line -> (DialogBody) DialogBody.plainMessage(component(player, line), request.bodyWidth()))
                .toList();
        DialogBase base = DialogBase.builder(component(player, request.title()))
                .externalTitle(component(player, request.title()))
                .canCloseWithEscape(request.canCloseWithEscape())
                .pause(request.pause())
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(List.of(DialogInput.text("value", component(player, request.fieldLabel()))
                        .width(request.inputWidth())
                        .labelVisible(request.labelVisible())
                        .initial(initial)
                        .maxLength(request.maxLength())
                        .build()))
                .build();
        DialogAction confirmAction = DialogAction.customClick((view, audience) -> {
            String input = view == null ? "" : view.getText("value");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) onSubmit.accept(input == null ? "" : input);
            });
        }, CALLBACK);
        DialogAction cancelAction = DialogAction.customClick((view, audience) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) onCancel.run();
                }), CALLBACK);
        List<ActionButton> buttons = new ArrayList<>(2);
        for (String id : request.buttonOrder()) {
            if (id.equals("confirm")) buttons.add(button(player, request.confirm(), confirmAction));
            else buttons.add(button(player, request.cancel(), cancelAction));
        }
        Dialog dialog = Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(buttons, null, 2)));
        ((Audience) player).showDialog(dialog);
        return true;
    }

    private static ActionButton button(Player player, SearchDialogConfig.Button button, DialogAction action) {
        String label = button.icon().isBlank() ? button.label() : DialogIcons.withIcon(button.label(), button.icon());
        return ActionButton.create(component(player, label), component(player, button.tooltip()), button.width(), action);
    }

    private static Component component(Player player, String value) {
        return DialogIcons.inlineTokens(player, LEGACY.deserialize(FoText.color(value)));
    }
}
