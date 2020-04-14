/*
 * Copyright (C) 2017 The Android Open Source Project
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

package androidx.navigation;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.collection.SparseArrayCompat;
import androidx.navigation.common.R;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * NavGraph is a collection of {@link NavDestination} nodes fetchable by ID.
 *
 * <p>A NavGraph serves as a 'virtual' destination: while the NavGraph itself will not appear
 * on the back stack, navigating to the NavGraph will cause the
 * {@link #getStartDestination starting destination} to be added to the back stack.</p>
 */
public class NavGraph extends NavDestination implements Iterable<NavDestination> {
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final SparseArrayCompat<NavDestination> mNodes = new SparseArrayCompat<>();
    private int mStartDestId;
    private String mStartDestIdName;

    /**
     * Construct a new NavGraph. This NavGraph is not valid until you
     * {@link #addDestination(NavDestination) add a destination} and
     * {@link #setStartDestination(int) set the starting destination}.
     *
     * @param navGraphNavigator The {@link NavGraphNavigator} which this destination
     *                          will be associated with. Generally retrieved via a
     *                          {@link NavController}'s
     *                          {@link NavigatorProvider#getNavigator(Class)} method.
     */
    public NavGraph(@NonNull Navigator<? extends NavGraph> navGraphNavigator) {
        super(navGraphNavigator);
    }

    @Override
    public void onInflate(@NonNull Context context, @NonNull AttributeSet attrs) {
        super.onInflate(context, attrs);
        TypedArray a = context.getResources().obtainAttributes(attrs,
                R.styleable.NavGraphNavigator);
        setStartDestination(
                a.getResourceId(R.styleable.NavGraphNavigator_startDestination, 0));
        mStartDestIdName = getDisplayName(context, mStartDestId);
        a.recycle();
    }

    @Override
    @Nullable
    DeepLinkMatch matchDeepLink(@NonNull NavDeepLinkRequest request) {
        // First search through any deep links directly added to this NavGraph
        DeepLinkMatch bestMatch = super.matchDeepLink(request);
        // Then search through all child destinations for a matching deep link
        for (NavDestination child : this) {
            DeepLinkMatch childBestMatch = child.matchDeepLink(request);
            if (childBestMatch != null && (bestMatch == null
                    || childBestMatch.compareTo(bestMatch) > 0)) {
                bestMatch = childBestMatch;
            }
        }
        return bestMatch;
    }

    /**
     * Adds a destination to this NavGraph. The destination must have an
     * {@link NavDestination#getId()} id} set.
     *
     * <p>The destination must not have a {@link NavDestination#getParent() parent} set. If
     * the destination is already part of a {@link NavGraph navigation graph}, call
     * {@link #remove(NavDestination)} before calling this method.</p>
     *
     * @param node destination to add
     */
    public final void addDestination(@NonNull NavDestination node) {
        if (node.getId() == 0) {
            throw new IllegalArgumentException("Destinations must have an id."
                    + " Call setId() or include an android:id in your navigation XML.");
        }
        NavDestination existingDestination = mNodes.get(node.getId());
        if (existingDestination == node) {
            return;
        }
        if (node.getParent() != null) {
            throw new IllegalStateException("Destination already has a parent set."
                    + " Call NavGraph.remove() to remove the previous parent.");
        }
        if (existingDestination != null) {
            existingDestination.setParent(null);
        }
        node.setParent(this);
        mNodes.put(node.getId(), node);
    }

    /**
     * Adds multiple destinations to this NavGraph. Each destination must have an
     * {@link NavDestination#getId()} id} set.
     *
     * <p> Each destination must not have a {@link NavDestination#getParent() parent} set. If
     * any destination is already part of a {@link NavGraph navigation graph}, call
     * {@link #remove(NavDestination)} before calling this method.</p>
     *
     * @param nodes destinations to add
     */
    public final void addDestinations(@NonNull Collection<NavDestination> nodes) {
        for (NavDestination node : nodes) {
            if (node == null) {
                continue;
            }
            addDestination(node);
        }
    }

    /**
     * Adds multiple destinations to this NavGraph. Each destination must have an
     * {@link NavDestination#getId()} id} set.
     *
     * <p> Each destination must not have a {@link NavDestination#getParent() parent} set. If
     * any destination is already part of a {@link NavGraph navigation graph}, call
     * {@link #remove(NavDestination)} before calling this method.</p>
     *
     * @param nodes destinations to add
     */
    public final void addDestinations(@NonNull NavDestination... nodes) {
        for (NavDestination node : nodes) {
            if (node == null) {
                continue;
            }
            addDestination(node);
        }
    }

    /**
     * Finds a destination in the collection by ID. This will recursively check the
     * {@link #getParent() parent} of this navigation graph if node is not found in
     * this navigation graph.
     *
     * @param resid ID to locate
     * @return the node with ID resid
     */
    @Nullable
    public final NavDestination findNode(@IdRes int resid) {
        return findNode(resid, true);
    }

    @Nullable
    final NavDestination findNode(@IdRes int resid, boolean searchParents) {
        NavDestination destination = mNodes.get(resid);
        // Search the parent for the NavDestination if it is not a child of this navigation graph
        // and searchParents is true
        return destination != null
                ? destination
                : searchParents && getParent() != null ? getParent().findNode(resid) : null;
    }

    @NonNull
    @Override
    public final Iterator<NavDestination> iterator() {
        return new Iterator<NavDestination>() {
            private int mIndex = -1;
            private boolean mWentToNext = false;

            @Override
            public boolean hasNext() {
                return mIndex + 1 < mNodes.size();
            }

            @Override
            public NavDestination next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                mWentToNext = true;
                return mNodes.valueAt(++mIndex);
            }

            @Override
            public void remove() {
                if (!mWentToNext) {
                    throw new IllegalStateException(
                            "You must call next() before you can remove an element");
                }
                mNodes.valueAt(mIndex).setParent(null);
                mNodes.removeAt(mIndex);
                mIndex--;
                mWentToNext = false;
            }
        };
    }

    /**
     * Add all destinations from another collection to this one. As each destination has at most
     * one parent, the destinations will be removed from the given NavGraph.
     *
     * @param other collection of destinations to add. All destinations will be removed from this
     * graph after being added to this graph.
     */
    public final void addAll(@NonNull NavGraph other) {
        Iterator<NavDestination> iterator = other.iterator();
        while (iterator.hasNext()) {
            NavDestination destination = iterator.next();
            iterator.remove();
            addDestination(destination);
        }
    }

    /**
     * Remove a given destination from this NavGraph
     *
     * @param node the destination to remove.
     */
    public final void remove(@NonNull NavDestination node) {
        int index = mNodes.indexOfKey(node.getId());
        if (index >= 0) {
            mNodes.valueAt(index).setParent(null);
            mNodes.removeAt(index);
        }
    }

    /**
     * Clear all destinations from this navigation graph.
     */
    public final void clear() {
        Iterator<NavDestination> iterator = iterator();
        while (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @Override
    public String getDisplayName() {
        return getId() != 0 ? super.getDisplayName() : "the root navigation";
    }

    /**
     * Returns the starting destination for this NavGraph. When navigating to the NavGraph, this
     * destination is the one the user will initially see.
     * @return
     */
    @IdRes
    public final int getStartDestination() {
        return mStartDestId;
    }

    /**
     * Sets the starting destination for this NavGraph.
     *
     * @param startDestId The id of the destination to be shown when navigating to this NavGraph.
     */
    public final void setStartDestination(@IdRes int startDestId) {
        mStartDestId = startDestId;
        mStartDestIdName = null;
    }

    @NonNull
    String getStartDestDisplayName() {
        if (mStartDestIdName == null) {
            mStartDestIdName = Integer.toString(mStartDestId);
        }
        return mStartDestIdName;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        sb.append(" startDestination=");
        NavDestination startDestination = findNode(getStartDestination());
        if (startDestination == null) {
            if (mStartDestIdName == null) {
                sb.append("0x");
                sb.append(Integer.toHexString(mStartDestId));
            } else {
                sb.append(mStartDestIdName);
            }
        } else {
            sb.append("{");
            sb.append(startDestination.toString());
            sb.append("}");
        }
        return sb.toString();
    }
}
