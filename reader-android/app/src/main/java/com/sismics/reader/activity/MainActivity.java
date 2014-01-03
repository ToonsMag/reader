package com.sismics.reader.activity;

import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.provider.SearchRecentSuggestions;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SearchView;

import com.loopj.android.http.JsonHttpResponseHandler;
import com.sismics.reader.R;
import com.sismics.reader.fragment.AddSubscriptionDialogFragment;
import com.sismics.reader.fragment.ArticlesDefaultFragment;
import com.sismics.reader.fragment.ArticlesFragment;
import com.sismics.reader.model.application.ApplicationContext;
import com.sismics.reader.provider.RecentSuggestionsProvider;
import com.sismics.reader.resource.SubscriptionResource;
import com.sismics.reader.resource.UserResource;
import com.sismics.reader.ui.adapter.SharedArticlesAdapterHelper;
import com.sismics.reader.ui.adapter.SubscriptionAdapter;
import com.sismics.reader.ui.adapter.SubscriptionItem;
import com.sismics.reader.util.PreferenceUtil;

import org.apache.http.Header;
import org.json.JSONObject;

/**
 * Main activity.
 * 
 * @author bgamard
 */
public class MainActivity extends FragmentActivity {
    
    private static final String ARTICLES_FRAGMENT_TAG = "articlesFragment";
    private static final String ARTICLES_DEFAULT_FRAGMENT_TAG = "articlesDefaultFragment";
    private DrawerLayout drawerLayout;
    private ListView drawerList;
    private View drawer;
    private ActionBarDrawerToggle drawerToggle;
    private MenuItem searchItem;
    private boolean destroyed = false;
    private int defaultSubscription;
    
    @Override
    protected void onCreate(final Bundle args) {
        super.onCreate(args);

        // Check if logged in
        if (!ApplicationContext.getInstance().isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // Find the default subscription
        defaultSubscription = PreferenceUtil.getIntegerPreference(this, PreferenceUtil.PREF_DEFAULT_SUBSCRIPTION, 1);

        // Setup the activity
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.main_activity);

        // Cache view references
        drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawerList = (ListView) findViewById(R.id.drawer_list);
        drawer = findViewById(R.id.left_drawer);
        drawerList.setEmptyView(findViewById(R.id.progressBarDrawer));

        // Load subscriptions and select unread item
        if (args == null) {
            refreshSubscriptions(defaultSubscription, false);
        } else {
            refreshSubscriptions(args.getInt("drawerItemSelected", defaultSubscription), false);
        }

        // Drawer item click listener
        drawerList.setOnItemClickListener(new ListView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selectItem(position, false, true);
            }
        });

        if (drawerLayout != null) {
            // Set a custom shadow that overlays the main content when the drawer opens
            drawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

            // Enable ActionBar app icon to behave as action to toggle nav drawer
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setHomeButtonEnabled(true);

            // ActionBarDrawerToggle ties together the the proper interactions
            // between the sliding drawer and the action bar app icon
            drawerToggle = new ActionBarDrawerToggle(
                    this,                  /* host Activity */
                    drawerLayout,         /* DrawerLayout object */
                    R.drawable.ic_drawer, R.string.drawer_open, R.string.drawer_close) {

                @Override
                public void onDrawerOpened(View drawerView) {
                    invalidateOptionsMenu();
                }

                @Override
                public void onDrawerClosed(View drawerView) {
                    invalidateOptionsMenu();
                }
            };
            drawerLayout.setDrawerListener(drawerToggle);
        }

        handleIntent(getIntent());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);

        // Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (drawerLayout != null) {
            // If the nav drawer is open, hide action items related to the content view
            boolean drawerOpen = drawerLayout.isDrawerOpen(drawer);
            menu.findItem(R.id.refresh).setVisible(!drawerOpen);
            menu.findItem(R.id.all_read).setVisible(!drawerOpen);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.logout:
                UserResource.logout(getApplicationContext(), new JsonHttpResponseHandler() {
                    @Override
                    public void onFinish() {
                        // Force logout in all cases, so the user is not stuck in case of network error
                        ApplicationContext.getInstance().setUserInfo(getApplicationContext(), null);
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                        finish();
                    }
                });
                return true;
            
            case R.id.all_read:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.all_read_title)
                .setMessage(R.string.all_read_message)
                .setCancelable(true)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String url = SharedArticlesAdapterHelper.getInstance().getUrl();
                        SubscriptionResource.read(MainActivity.this, url, new JsonHttpResponseHandler() {
                            @Override
                            public void onFinish() {
                                refreshSubscriptions(drawerList.getCheckedItemPosition(), true);
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                })
                .create().show();
                return true;

            case R.id.add_subscription:
                // Create the dialog fragment
                AddSubscriptionDialogFragment addSubscriptionDialogFragment = new AddSubscriptionDialogFragment();

                // Refresh the subscriptions when a new one is added
                addSubscriptionDialogFragment.setAddSubscriptionDialogListener(new AddSubscriptionDialogFragment.AddSubscriptionDialogListener() {
                    @Override
                    public void onSubscriptionAdded(JSONObject json) {
                        refreshSubscriptions(defaultSubscription, true);
                    }
                });

                // Show the dialog
                addSubscriptionDialogFragment.show(getSupportFragmentManager(), "AddSubscriptionDialogFragment");
                return true;

            case R.id.settings:
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return true;

            case R.id.refresh:
                // Refresh subscriptions and articles
                refreshSubscriptions(drawerList.getCheckedItemPosition(), true);
                return true;
            
            case android.R.id.home:
                // The action bar home/up action should open or close the drawer.
                // ActionBarDrawerToggle will take care of this.
                if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) {
                    return true;
                }
                return true;
            
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Select an item from the subscription list.
     * @param position Position to select
     * @param refresh True to force articles refresh
     * @param closeDrawer If true, close the drawer
     */
    private void selectItem(int position, boolean refresh, boolean closeDrawer) {
        // Create a new fragment with articles context
        SubscriptionAdapter adapter = (SubscriptionAdapter) drawerList.getAdapter();
        if (adapter == null) {
            return;
        }
        
        SubscriptionItem item = adapter.getItem(position);
        if (item == null) {
            return;
        }
        
        Fragment fragment = new ArticlesFragment();
        Bundle args = new Bundle();
        if (item.getType() == SubscriptionItem.SUBSCRIPTION_ITEM) {
            // ID is only given for subscription, to show the favicon
            args.putString("id", item.getId());
        }
        args.putString("title", item.getTitle());
        args.putString("url", item.getUrl());
        args.putBoolean("unread", item.isUnread());
        fragment.setArguments(args);

        // Update the main content by replacing fragment if it has different arguments from the previous one
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment oldFragment = fragmentManager.findFragmentByTag(ARTICLES_FRAGMENT_TAG);
        
        boolean replace = true;
        if (oldFragment != null && !refresh) {
            Bundle oldArgs = oldFragment.getArguments();
            if (oldArgs != null) {
                if (args.getString("url").equals(oldArgs.getString("url"))
                        && args.getBoolean("unread") == oldArgs.getBoolean("unread")) {
                    replace = false;
                }
            }
        }
        
        if (replace) {
            fragmentManager.beginTransaction().replace(R.id.content_frame, fragment, ARTICLES_FRAGMENT_TAG).commitAllowingStateLoss();
        }

        // Update selected item and title
        drawerList.setItemChecked(position, true);
        setTitle(item.getTitle());
        
        // Close the drawer if asked
        if (closeDrawer && drawerLayout != null) {
            drawerLayout.closeDrawer(drawer);
        }
    }

    /**
     * Refresh subscriptions list from server.
     * @param position Position to select
     * @param refresh True to force articles refresh
     */
    private void refreshSubscriptions(final int position, final boolean refresh) {
        // Callback when JSON data from subscriptions needs to be displayed
        JsonHttpResponseHandler callback = new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(JSONObject json) {
                // Cache the JSON
                PreferenceUtil.setCachedJson(getApplicationContext(), PreferenceUtil.PREF_CACHED_SUBSCRIPTION_JSON, json);
                
                if (isActivityDestroyed()) {
                    return;
                }
                
                // Update or create adapter
                SubscriptionAdapter adapter = (SubscriptionAdapter) drawerList.getAdapter();
                if (adapter == null) {
                    adapter = new SubscriptionAdapter(MainActivity.this, json);
                    drawerList.setAdapter(adapter);
                } else {
                    adapter.setItems(json);
                    adapter.notifyDataSetChanged();
                }
                
                // Check if the item exists and is selectable
                int pos = position;
                if (!adapter.isEnabled(pos)) {
                    pos = defaultSubscription;
                }
                selectItem(pos, refresh, false);
            }

            @Override
            public void onFailure(final int statusCode, final Header[] headers, final byte[] responseBytes, final Throwable throwable) {
                ArticlesDefaultFragment articlesDefaultFragment =  (ArticlesDefaultFragment) getSupportFragmentManager().findFragmentByTag(ARTICLES_DEFAULT_FRAGMENT_TAG);
                articlesDefaultFragment.onSubscriptionError();
            }
        };
        
        if (refresh) {
            // Show a default fragment while the subscriptions are loading
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, new ArticlesDefaultFragment(), ARTICLES_DEFAULT_FRAGMENT_TAG)
                .commitAllowingStateLoss();
        } else {
            // Show the cache first
            JSONObject cache = PreferenceUtil.getCachedJson(getApplicationContext(), PreferenceUtil.PREF_CACHED_SUBSCRIPTION_JSON);
            if (cache != null) {
                callback.onSuccess(cache);
            }
        }
            
        // Load subscriptions from server
        boolean subscriptionUnreadPref = PreferenceUtil.getBooleanPreference(this, PreferenceUtil.PREF_SUBSCRIPTION_UNREAD, false);
        SubscriptionResource.cancel(this);
        SubscriptionResource.list(this, subscriptionUnreadPref, callback);
    }
    
    /**
     * When using the ActionBarDrawerToggle, you must call it during
     * onPostCreate() and onConfigurationChanged()...
     */
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (drawerToggle != null) {
            // Sync the toggle state after onRestoreInstanceState has occurred.
            drawerToggle.syncState();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (drawerToggle != null) {
            // Pass any configuration change to the drawer toggle
            drawerToggle.onConfigurationChanged(newConfig);
        }
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("drawerItemSelected", drawerList.getCheckedItemPosition());
    }
    
    /**
     * Return true if the activity has been destroyed (not present until API 17).
     * @return True if the activity has been destroyed
     */
    public boolean isActivityDestroyed() {
        return destroyed;
    }
    
    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Handle the incoming intent.
     * @param intent
     */
    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // Perform a search query
            String query = intent.getStringExtra(SearchManager.QUERY);

            // Collapse the SearchView
            if (searchItem != null) {
                searchItem.collapseActionView();
            }

            // Save the query
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this, RecentSuggestionsProvider.AUTHORITY, RecentSuggestionsProvider.MODE);
            suggestions.saveRecentQuery(query, null);

            // Prepare the fragment
            Fragment fragment = new ArticlesFragment();
            Bundle args = new Bundle();
            args.putString("title", query);
            args.putString("url", "/search/" + query);
            args.putBoolean("unread", false);
            fragment.setArguments(args);

            // Update the main content by replacing fragment
            FragmentManager fragmentManager = getSupportFragmentManager();
            fragmentManager.beginTransaction().replace(R.id.content_frame, fragment, ARTICLES_FRAGMENT_TAG).commitAllowingStateLoss();

            // Update selected item and title
            drawerList.setItemChecked(drawerList.getCheckedItemPosition(), false);
            setTitle(query);
        }
    }
}