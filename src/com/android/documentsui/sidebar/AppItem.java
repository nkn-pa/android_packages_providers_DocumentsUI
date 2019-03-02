/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui.sidebar;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.documentsui.ActionHandler;
import com.android.documentsui.IconUtils;
import com.android.documentsui.R;

/**
 * An {@link Item} for apps that supports some picking actions like
 * {@link Intent#ACTION_GET_CONTENT} such as Photos. This is only used in pickers.
 */
public class AppItem extends Item {
    private static final String STRING_ID_FORMAT = "AppItem{%s/%s}";

    public final ResolveInfo info;

    private final ActionHandler mActionHandler;

    public AppItem(ResolveInfo info, String title, ActionHandler actionHandler) {
        super(R.layout.item_root, title, getStringId(info));
        this.info = info;
        mActionHandler = actionHandler;
    }

    private static String getStringId(ResolveInfo info) {
        ActivityInfo activityInfo = info.activityInfo;

        String component = String.format(
                STRING_ID_FORMAT, activityInfo.applicationInfo.packageName, activityInfo.name);
        return component;
    }

    protected void bindActionIcon(View actionIconArea, ImageView actionIcon) {
        actionIconArea.setVisibility(View.VISIBLE);
        actionIconArea.setFocusable(false);
        actionIcon.setImageDrawable(
                IconUtils.applyTintColor(actionIcon.getContext(), R.drawable.ic_exit_to_app,
                        R.color.item_action_icon));

    }

    @Override
    boolean showAppDetails() {
        mActionHandler.showAppDetails(info);
        return true;
    }

    @Override
    void bindView(View convertView) {
        final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
        final TextView titleView = (TextView) convertView.findViewById(android.R.id.title);
        final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);
        final View actionIconArea = convertView.findViewById(R.id.action_icon_area);
        final ImageView actionIcon = (ImageView) convertView.findViewById(R.id.action_icon);

        final Context context = convertView.getContext();
        final PackageManager pm = context.getPackageManager();
        icon.setImageDrawable(info.loadIcon(pm));
        titleView.setText(title);

        bindActionIcon(actionIconArea, actionIcon);

        // TODO: match existing summary behavior from disambig dialog
        summary.setVisibility(View.GONE);
    }

    @Override
    boolean isRoot() {
        // We won't support drag n' drop in pickers, and apps only show up there.
        return false;
    }

    @Override
    void open() {
        mActionHandler.openRoot(info);
    }

    @Override
    String getPackageName() {
        return info.activityInfo.packageName;
    }

    @Override
    public String toString() {
        return "AppItem{"
                + "id=" + stringId
                + ", resolveInfo=" + info
                + "}";
    }
}
