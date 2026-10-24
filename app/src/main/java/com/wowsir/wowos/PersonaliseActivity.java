package com.wowsir.wowos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.List;

/** Your name, the greeting, and your own messages. */
public class PersonaliseActivity extends Activity {

    private View nameRow;
    private View showRow;
    private View builtinRow;
    private View mineRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.page(this, "Personalise", "Your greeting and the messages on the home screen.");
        LinearLayout content = Ui.content(this);

        LinearLayout g = Ui.card(this, content, R.drawable.ic_star, "Greeting");
        nameRow = Ui.row(this, g, R.drawable.ic_keyboard, "Your name", null, "", new Runnable() {
            @Override public void run() { askName(); }
        });
        showRow = Ui.row(this, g, R.drawable.ic_star, "Show greeting and messages", null, "", new Runnable() {
            @Override public void run() { flip(Greeting.K_SHOW); }
        });

        LinearLayout m = Ui.card(this, content, R.drawable.ic_doc, "Messages");
        builtinRow = Ui.row(this, m, R.drawable.ic_star, "WOW OS messages",
                "Original lines on courage and letting your light shine", "", new Runnable() {
                    @Override public void run() { flip(Greeting.K_BUILTIN); }
                });
        mineRow = Ui.row(this, m, R.drawable.ic_doc, "My messages",
                "Add your favourite quotes. They stay on this box only.", "", new Runnable() {
                    @Override public void run() { manageMine(); }
                });
        refresh();
    }

    private void refresh() {
        Ui.setValue(nameRow, Greeting.name(this));
        Ui.setValue(showRow, Greeting.show(this) ? "On" : "Off");
        Ui.setValue(builtinRow, Greeting.builtin(this) ? "On" : "Off");
        int n = Greeting.mine(this).size();
        Ui.setValue(mineRow, n == 0 ? "None yet" : n + (n == 1 ? " message" : " messages"));
    }

    private void flip(String key) {
        boolean v = !Greeting.prefs(this).getBoolean(key, true);
        Greeting.prefs(this).edit().putBoolean(key, v).apply();
        refresh();
    }

    private void askName() {
        ask("Your name", Greeting.name(this), false, new Done() {
            @Override public void done(String text) {
                Greeting.prefs(PersonaliseActivity.this).edit().putString(Greeting.K_NAME, text).apply();
                refresh();
            }
        });
    }

    private void manageMine() {
        final List<String> mine = Greeting.mine(this);
        String[] items = new String[mine.size() + 1];
        items[0] = "+ Add a message";
        for (int i = 0; i < mine.size(); i++) items[i + 1] = mine.get(i);
        glass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(mine.isEmpty() ? "My messages" : "My messages (choose one to remove it)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            ask("New message", "", true, new Done() {
                                @Override public void done(String text) {
                                    mine.add(text);
                                    Greeting.setMine(PersonaliseActivity.this, mine);
                                    refresh();
                                }
                            });
                        } else {
                            mine.remove(which - 1);
                            Greeting.setMine(PersonaliseActivity.this, mine);
                            refresh();
                        }
                    }
                })
                .setNegativeButton("Close", null).create());
    }

    private interface Done { void done(String text); }

    private void ask(String title, String initial, boolean multiLine, final Done done) {
        final EditText input = new EditText(this);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiLine ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multiLine) input.setMaxLines(6);
        FrameLayout box = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input);
        glass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // Messages are stored one per line, so keep each on a single line.
                        String t = input.getText().toString().replace('\n', ' ').trim();
                        if (!t.isEmpty()) done.done(t);
                    }
                }).create());
    }

    private void glass(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }
}
