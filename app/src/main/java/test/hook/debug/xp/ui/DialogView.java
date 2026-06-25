package test.hook.debug.xp.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;

/**
 * @author user
 *
 * The dialog is built programmatically (without module XML resources) because injecting
 * module resources into the host app (XResources) does not work on newer firmware (HyperOS 3+).
 */
public class DialogView {
    private final View view;
    private final ItemAdapter adapter;

    private DialogView(View view, ListView list) {
        this.view = view;
        this.adapter = new ItemAdapter();
        list.setAdapter(adapter);
    }

    public static DialogView create(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        ListView list = new ListView(context);
        list.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(list);

        return new DialogView(root, list);
    }

    public void addNode(String name, View.OnClickListener listener) {
        adapter.addNode(name, listener);
    }

    public View getView() {
        return view;
    }

}
