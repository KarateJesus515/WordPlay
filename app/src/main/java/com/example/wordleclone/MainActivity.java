package com.example.wordleclone;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int WORD_LENGTH = 5;
    private static final int MAX_ATTEMPTS = 6;
    private static final int SCREEN_TITLE = 1;
    private static final int SCREEN_GAME = 2;
    private static final int SCREEN_STATS = 3;

    private static final int COLOR_BACKGROUND = Color.rgb(18, 18, 19);
    private static final int COLOR_PANEL = Color.rgb(26, 26, 28);
    private static final int COLOR_TEXT = Color.rgb(248, 248, 248);
    private static final int COLOR_MUTED = Color.rgb(155, 155, 160);
    private static final int COLOR_TILE_EMPTY = Color.rgb(58, 58, 60);
    private static final int COLOR_KEY_EMPTY = Color.rgb(129, 131, 132);
    private static final int COLOR_ABSENT = Color.rgb(58, 58, 60);
    private static final int COLOR_PRESENT = Color.rgb(181, 159, 59);
    private static final int COLOR_CORRECT = Color.rgb(83, 141, 78);
    private static final String FIVE_LETTER_WORDS_URL =
            "https://api.datamuse.com/words?sp=%3F%3F%3F%3F%3F&md=f&max=1000";
    private static final String EXACT_WORD_URL =
            "https://api.datamuse.com/words?sp=%s&md=f&max=10";
    private static final String[] BACKUP_WORDS = {
            "APPLE", "BRAIN", "CHAIR", "DREAM", "EARTH", "FLAME", "GRAPE", "HOUSE"
    };

    private final TextView[][] tiles = new TextView[MAX_ATTEMPTS][WORD_LENGTH];
    private final LinearLayout[] rowViews = new LinearLayout[MAX_ATTEMPTS];
    private final Map<Character, TextView> keyViews = new HashMap<>();
    private final Map<Character, Integer> keyStates = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> onlineAnswerPool = new ArrayList<>();
    private final Set<String> validWordCache = new HashSet<>();
    private final Set<String> invalidWordCache = new HashSet<>();

    private TextView statusText;
    private String answer;
    private int currentRow;
    private int currentCol;
    private boolean gameOver;
    private boolean requestInFlight;
    private int loadToken;
    private int currentScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);

        showTitleScreen();
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showTitleScreen() {
        currentScreen = SCREEN_TITLE;
        gameOver = true;
        requestInFlight = false;
        loadToken++;

        ScrollView scrollView = createBaseScrollView();
        LinearLayout root = createBaseRoot(scrollView);
        root.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("WORDPLAY");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(42);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText("Unlimited online word puzzles");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(42);
        root.addView(subtitle, subtitleParams);

        TextView newGameButton = makeMenuButton("NEW GAME");
        newGameButton.setOnClickListener(v -> startNewGame());
        root.addView(newGameButton, menuButtonParams());

        TextView statsButton = makeMenuButton("STATS");
        statsButton.setOnClickListener(v -> showStatsScreen());
        root.addView(statsButton, menuButtonParams());

        TextView exitButton = makeMenuButton("EXIT");
        exitButton.setBackground(roundBackground(COLOR_ABSENT, dp(8)));
        exitButton.setOnClickListener(v -> finish());
        root.addView(exitButton, menuButtonParams());

        setContentView(scrollView);
    }

    private void showGameScreen() {
        currentScreen = SCREEN_GAME;
        keyViews.clear();

        ScrollView scrollView = createBaseScrollView();
        LinearLayout root = createBaseRoot(scrollView);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("WORDPLAY");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView exitButton = makeHeaderButton("EXIT");
        exitButton.setOnClickListener(v -> confirmQuitGame());
        header.addView(exitButton, new LinearLayout.LayoutParams(dp(82), dp(42)));

        statusText = new TextView(this);
        statusText.setTextColor(COLOR_MUTED);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Guess the hidden 5-letter word.");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        root.addView(statusText, statusParams);

        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        boardParams.topMargin = dp(8);
        boardParams.bottomMargin = dp(18);
        root.addView(board, boardParams);

        for (int row = 0; row < MAX_ATTEMPTS; row++) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            rowViews[row] = rowLayout;

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(58)
            );
            rowParams.bottomMargin = dp(6);
            board.addView(rowLayout, rowParams);

            for (int col = 0; col < WORD_LENGTH; col++) {
                TextView tile = new TextView(this);
                tile.setGravity(Gravity.CENTER);
                tile.setTextColor(COLOR_TEXT);
                tile.setTextSize(26);
                tile.setTypeface(Typeface.DEFAULT_BOLD);
                tile.setBackground(tileBackground(COLOR_BACKGROUND, COLOR_TILE_EMPTY));

                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
                tileParams.leftMargin = dp(3);
                tileParams.rightMargin = dp(3);
                rowLayout.addView(tile, tileParams);
                tiles[row][col] = tile;
            }
        }

        buildKeyboard(root);
        setContentView(scrollView);
    }

    private ScrollView createBaseScrollView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        return scrollView;
    }

    private LinearLayout createBaseRoot(ScrollView scrollView) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(16));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        return root;
    }

    private TextView makeHeaderButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(roundBackground(COLOR_PANEL, dp(8)));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView makeMenuButton(String label) {
        TextView button = makeHeaderButton(label);
        button.setTextSize(18);
        button.setBackground(roundBackground(COLOR_CORRECT, dp(8)));
        return button;
    }

    private LinearLayout.LayoutParams menuButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
        );
        params.leftMargin = dp(24);
        params.rightMargin = dp(24);
        params.topMargin = dp(12);
        return params;
    }

    private void buildKeyboard(LinearLayout root) {
        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setGravity(Gravity.CENTER);
        root.addView(keyboard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        addKeyboardRow(keyboard, "QWERTYUIOP");
        addKeyboardRow(keyboard, "ASDFGHJKL");

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        rowParams.topMargin = dp(7);
        keyboard.addView(bottomRow, rowParams);

        TextView enter = makeKey("ENTER");
        enter.setOnClickListener(v -> submitGuess());
        bottomRow.addView(enter, keyParams(1.55f));

        for (char c : "ZXCVBNM".toCharArray()) {
            TextView key = makeKey(String.valueOf(c));
            key.setOnClickListener(v -> addLetter(((TextView) v).getText().charAt(0)));
            keyViews.put(c, key);
            bottomRow.addView(key, keyParams(1f));
        }

        TextView delete = makeKey("DEL");
        delete.setOnClickListener(v -> deleteLetter());
        bottomRow.addView(delete, keyParams(1.3f));
    }

    private void addKeyboardRow(LinearLayout keyboard, String letters) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        rowParams.topMargin = dp(7);
        keyboard.addView(row, rowParams);

        for (char c : letters.toCharArray()) {
            TextView key = makeKey(String.valueOf(c));
            key.setOnClickListener(v -> addLetter(((TextView) v).getText().charAt(0)));
            keyViews.put(c, key);
            row.addView(key, keyParams(1f));
        }
    }

    private LinearLayout.LayoutParams keyParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private TextView makeKey(String label) {
        TextView key = new TextView(this);
        key.setText(label);
        key.setGravity(Gravity.CENTER);
        key.setTextColor(COLOR_TEXT);
        key.setTextSize(label.length() > 1 ? 12 : 18);
        key.setTypeface(Typeface.DEFAULT_BOLD);
        key.setBackground(roundBackground(COLOR_KEY_EMPTY, dp(6)));
        key.setClickable(true);
        key.setFocusable(true);
        return key;
    }

    private void startNewGame() {
        showGameScreen();
        answer = "";
        currentRow = 0;
        currentCol = 0;
        gameOver = true;
        requestInFlight = true;
        keyStates.clear();
        int token = ++loadToken;
        statusText.setText("Loading an online word...");
        statusText.setTextColor(COLOR_MUTED);

        for (int row = 0; row < MAX_ATTEMPTS; row++) {
            for (int col = 0; col < WORD_LENGTH; col++) {
                tiles[row][col].setText("");
                tiles[row][col].setBackground(tileBackground(COLOR_BACKGROUND, COLOR_TILE_EMPTY));
            }
        }

        for (TextView key : keyViews.values()) {
            key.setBackground(roundBackground(COLOR_KEY_EMPTY, dp(6)));
        }

        networkExecutor.execute(() -> {
            OnlineWordResult result = fetchRandomAnswer();
            mainHandler.post(() -> {
                if (token != loadToken) {
                    return;
                }

                answer = result.word;
                gameOver = false;
                requestInFlight = false;
                validWordCache.add(answer);
                showStatus(
                        result.fromOnline
                                ? "Online word ready. Start guessing."
                                : "Using a backup word. Check your connection.",
                        result.fromOnline
                );
            });
        });
    }

    private void confirmQuitGame() {
        new AlertDialog.Builder(this)
                .setTitle("Quit game?")
                .setMessage("Your current puzzle will be abandoned.")
                .setPositiveButton("Quit", (dialog, which) -> {
                    gameOver = true;
                    requestInFlight = false;
                    loadToken++;
                    showTitleScreen();
                })
                .setNegativeButton("Keep playing", null)
                .show();
    }

    private void addLetter(char letter) {
        if (gameOver || requestInFlight || currentCol >= WORD_LENGTH) {
            return;
        }
        char upper = Character.toUpperCase(letter);
        if (upper < 'A' || upper > 'Z') {
            return;
        }
        tiles[currentRow][currentCol].setText(String.valueOf(upper));
        tiles[currentRow][currentCol].setBackground(tileBackground(COLOR_PANEL, COLOR_TILE_EMPTY));
        currentCol++;
        statusText.setText("");
    }

    private void deleteLetter() {
        if (gameOver || requestInFlight || currentCol <= 0) {
            return;
        }
        currentCol--;
        tiles[currentRow][currentCol].setText("");
        tiles[currentRow][currentCol].setBackground(tileBackground(COLOR_BACKGROUND, COLOR_TILE_EMPTY));
    }

    private void submitGuess() {
        if (gameOver || requestInFlight) {
            return;
        }

        if (currentCol < WORD_LENGTH) {
            showStatus("Not enough letters.", false);
            shakeCurrentRow();
            return;
        }

        String guess = readCurrentGuess();
        int token = loadToken;
        requestInFlight = true;
        showStatus("Checking " + guess + " online...", true);

        networkExecutor.execute(() -> {
            boolean valid = isValidWordOnline(guess);
            mainHandler.post(() -> {
                if (token != loadToken || gameOver) {
                    return;
                }

                requestInFlight = false;
                if (!valid) {
                    showStatus("Not found in the online dictionary.", false);
                    shakeCurrentRow();
                    return;
                }

                finishValidGuess(guess);
            });
        });
    }

    private void finishValidGuess(String guess) {
        int[] result = scoreGuess(guess, answer);
        revealGuess(guess, result);

        if (guess.equals(answer)) {
            gameOver = true;
            saveGameResult(true, currentRow + 1);
            showStatus("You got it in " + (currentRow + 1) + "!", true);
            showGameOverDialog(true);
            return;
        }

        currentRow++;
        currentCol = 0;

        if (currentRow == MAX_ATTEMPTS) {
            gameOver = true;
            saveGameResult(false, 0);
            showStatus("The word was " + answer + ".", false);
            showGameOverDialog(false);
        } else {
            showStatus("Try another word.", true);
        }
    }

    private String readCurrentGuess() {
        StringBuilder builder = new StringBuilder();
        for (int col = 0; col < WORD_LENGTH; col++) {
            builder.append(tiles[currentRow][col].getText());
        }
        return builder.toString().toUpperCase(Locale.US);
    }

    private int[] scoreGuess(String guess, String target) {
        int[] result = new int[WORD_LENGTH];
        Map<Character, Integer> remaining = new HashMap<>();

        for (int i = 0; i < WORD_LENGTH; i++) {
            char guessLetter = guess.charAt(i);
            char targetLetter = target.charAt(i);
            if (guessLetter == targetLetter) {
                result[i] = 3;
            } else {
                remaining.put(targetLetter, getIntOrZero(remaining, targetLetter) + 1);
            }
        }

        for (int i = 0; i < WORD_LENGTH; i++) {
            if (result[i] == 3) {
                continue;
            }

            char guessLetter = guess.charAt(i);
            int count = getIntOrZero(remaining, guessLetter);
            if (count > 0) {
                result[i] = 2;
                remaining.put(guessLetter, count - 1);
            } else {
                result[i] = 1;
            }
        }

        return result;
    }

    private void revealGuess(String guess, int[] result) {
        for (int i = 0; i < WORD_LENGTH; i++) {
            int color = colorForState(result[i]);
            tiles[currentRow][i].setBackground(tileBackground(color, color));
            updateKeyboardColor(guess.charAt(i), result[i]);
        }
    }

    private void updateKeyboardColor(char letter, int state) {
        int currentState = getIntOrZero(keyStates, letter);
        if (state <= currentState) {
            return;
        }

        keyStates.put(letter, state);
        TextView key = keyViews.get(letter);
        if (key != null) {
            key.setBackground(roundBackground(colorForState(state), dp(6)));
        }
    }

    private int getIntOrZero(Map<Character, Integer> map, char key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }

    private int colorForState(int state) {
        if (state == 3) {
            return COLOR_CORRECT;
        }
        if (state == 2) {
            return COLOR_PRESENT;
        }
        return COLOR_ABSENT;
    }

    private void showStatus(String message, boolean positive) {
        statusText.setText(message);
        statusText.setTextColor(positive ? COLOR_TEXT : Color.rgb(255, 142, 142));
    }

    private void shakeCurrentRow() {
        ObjectAnimator animator = ObjectAnimator.ofFloat(
                rowViews[currentRow],
                "translationX",
                0f, -14f, 14f, -10f, 10f, -5f, 5f, 0f
        );
        animator.setDuration(360);
        animator.start();
    }

    private void showGameOverDialog(boolean won) {
        String message = won
                ? "Nice work. The word was " + answer + "."
                : "Good try. The word was " + answer + ".";

        new AlertDialog.Builder(this)
                .setTitle(won ? "You won" : "Game over")
                .setMessage(message)
                .setPositiveButton("New game", (dialog, which) -> startNewGame())
                .setNegativeButton("Stats", (dialog, which) -> showStatsScreen())
                .setNeutralButton("Title", (dialog, which) -> showTitleScreen())
                .show();
    }

    private void saveGameResult(boolean won, int attemptsUsed) {
        SharedPreferences prefs = getSharedPreferences("wordplay_stats", MODE_PRIVATE);
        int played = prefs.getInt("played", 0) + 1;
        int wins = prefs.getInt("wins", 0) + (won ? 1 : 0);
        int streak = won ? prefs.getInt("streak", 0) + 1 : 0;
        int maxStreak = Math.max(streak, prefs.getInt("max_streak", 0));

        SharedPreferences.Editor editor = prefs.edit()
                .putInt("played", played)
                .putInt("wins", wins)
                .putInt("streak", streak)
                .putInt("max_streak", maxStreak);

        if (won) {
            String key = "guess_" + attemptsUsed;
            editor.putInt(key, prefs.getInt(key, 0) + 1);
        }

        editor.apply();
    }

    private void showStatsScreen() {
        currentScreen = SCREEN_STATS;
        gameOver = true;
        requestInFlight = false;
        loadToken++;

        SharedPreferences prefs = getSharedPreferences("wordplay_stats", MODE_PRIVATE);
        int played = prefs.getInt("played", 0);
        int wins = prefs.getInt("wins", 0);
        int winPercent = played == 0 ? 0 : Math.round((wins * 100f) / played);

        ScrollView scrollView = createBaseScrollView();
        LinearLayout root = createBaseRoot(scrollView);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("STATS");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));

        TextView backButton = makeHeaderButton("BACK");
        backButton.setOnClickListener(v -> showTitleScreen());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(82), dp(42)));

        LinearLayout statsPanel = new LinearLayout(this);
        statsPanel.setOrientation(LinearLayout.VERTICAL);
        statsPanel.setPadding(dp(18), dp(18), dp(18), dp(18));
        statsPanel.setBackground(roundBackground(COLOR_PANEL, dp(8)));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.topMargin = dp(26);
        root.addView(statsPanel, panelParams);

        addStatsLine(statsPanel, "Played", String.valueOf(played));
        addStatsLine(statsPanel, "Wins", String.valueOf(wins));
        addStatsLine(statsPanel, "Win rate", winPercent + "%");
        addStatsLine(statsPanel, "Current streak", String.valueOf(prefs.getInt("streak", 0)));
        addStatsLine(statsPanel, "Best streak", String.valueOf(prefs.getInt("max_streak", 0)));

        TextView distributionTitle = new TextView(this);
        distributionTitle.setText("Guess distribution");
        distributionTitle.setTextColor(COLOR_TEXT);
        distributionTitle.setTextSize(18);
        distributionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams distributionTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        distributionTitleParams.topMargin = dp(26);
        statsPanel.addView(distributionTitle, distributionTitleParams);

        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            addStatsLine(statsPanel, String.valueOf(i), String.valueOf(prefs.getInt("guess_" + i, 0)));
        }

        TextView resetButton = makeMenuButton("RESET STATS");
        resetButton.setBackground(roundBackground(COLOR_ABSENT, dp(8)));
        resetButton.setOnClickListener(v -> confirmResetStats());
        LinearLayout.LayoutParams resetParams = menuButtonParams();
        resetParams.topMargin = dp(28);
        root.addView(resetButton, resetParams);

        setContentView(scrollView);
    }

    private void addStatsLine(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
        );
        parent.addView(row, rowParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(COLOR_MUTED);
        labelView.setTextSize(16);
        row.addView(labelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(COLOR_TEXT);
        valueView.setTextSize(18);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setGravity(Gravity.RIGHT);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void confirmResetStats() {
        new AlertDialog.Builder(this)
                .setTitle("Reset stats?")
                .setMessage("This clears wins, streaks, and guess distribution.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    getSharedPreferences("wordplay_stats", MODE_PRIVATE).edit().clear().apply();
                    showStatsScreen();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (currentScreen != SCREEN_GAME || requestInFlight) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            submitGuess();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            deleteLetter();
            return true;
        }

        int unicode = event.getUnicodeChar();
        if (unicode != 0) {
            char typed = Character.toUpperCase((char) unicode);
            if (typed >= 'A' && typed <= 'Z') {
                addLetter(typed);
                return true;
            }
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == SCREEN_GAME) {
            confirmQuitGame();
            return;
        }
        if (currentScreen == SCREEN_STATS) {
            showTitleScreen();
            return;
        }

        super.onBackPressed();
    }

    private OnlineWordResult fetchRandomAnswer() {
        try {
            List<String> freshWords = fetchFiveLetterWords();
            if (!freshWords.isEmpty()) {
                onlineAnswerPool.clear();
                onlineAnswerPool.addAll(freshWords);
            }

            if (!onlineAnswerPool.isEmpty()) {
                return new OnlineWordResult(
                        onlineAnswerPool.get(random.nextInt(onlineAnswerPool.size())),
                        true
                );
            }
        } catch (Exception ignored) {
        }

        if (!onlineAnswerPool.isEmpty()) {
            return new OnlineWordResult(
                    onlineAnswerPool.get(random.nextInt(onlineAnswerPool.size())),
                    true
            );
        }

        return new OnlineWordResult(BACKUP_WORDS[random.nextInt(BACKUP_WORDS.length)], false);
    }

    private List<String> fetchFiveLetterWords() throws Exception {
        JSONArray response = new JSONArray(readUrl(FIVE_LETTER_WORDS_URL));
        List<String> words = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < response.length(); i++) {
            JSONObject item = response.getJSONObject(i);
            String word = item.optString("word", "").toUpperCase(Locale.US);
            if (word.matches("[A-Z]{5}") && seen.add(word)) {
                words.add(word);
                validWordCache.add(word);
            }
        }

        return words;
    }

    private boolean isValidWordOnline(String guess) {
        if (validWordCache.contains(guess)) {
            return true;
        }
        if (invalidWordCache.contains(guess)) {
            return false;
        }

        try {
            String lowerGuess = guess.toLowerCase(Locale.US);
            String url = String.format(Locale.US, EXACT_WORD_URL, lowerGuess);
            JSONArray response = new JSONArray(readUrl(url));

            for (int i = 0; i < response.length(); i++) {
                String word = response.getJSONObject(i).optString("word", "");
                if (lowerGuess.equals(word.toLowerCase(Locale.US))) {
                    validWordCache.add(guess);
                    return true;
                }
            }

            invalidWordCache.add(guess);
            return false;
        } catch (Exception ignored) {
            if (answer.equals(guess) || isBackupWord(guess)) {
                return true;
            }
        }

        return false;
    }

    private boolean isBackupWord(String guess) {
        for (String word : BACKUP_WORDS) {
            if (word.equals(guess)) {
                return true;
            }
        }
        return false;
    }

    private String readUrl(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");

        try {
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("HTTP " + statusCode);
            }

            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static class OnlineWordResult {
        final String word;
        final boolean fromOnline;

        OnlineWordResult(String word, boolean fromOnline) {
            this.word = word;
            this.fromOnline = fromOnline;
        }
    }

    private GradientDrawable tileBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(dp(2), strokeColor);
        drawable.setCornerRadius(dp(2));
        return drawable;
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
