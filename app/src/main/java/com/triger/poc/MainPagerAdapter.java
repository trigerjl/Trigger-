package com.triger.poc;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1:
                return new MiningFragment();
            case 2:
                return new MarketFragment();
            case 3:
                return new ProfileAndChatFragment();
            case 4:
                return new SettingsFragment();
            case 0:
            default:
                return new WalletFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 5;
    }
}
