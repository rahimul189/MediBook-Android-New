package com.example.medibook.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemWellnessCardBinding;
import com.example.medibook.wellness.WellnessCards;

/**
 * Pages for the Health & Wellness carousel. The pages are informational images only: this adapter
 * attaches no click or long-click listener anywhere, so a tap or press on a card does nothing.
 */
public final class WellnessCarouselAdapter extends RecyclerView.Adapter<WellnessCarouselAdapter.Holder> {

    private final int[] cards = WellnessCards.drawables();

    public WellnessCarouselAdapter() {
        setHasStableIds(true);
    }

    @Override public int getItemCount() { return cards.length; }

    @Override public long getItemId(int position) { return position; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemWellnessCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(position);
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final ItemWellnessCardBinding binding;

        Holder(ItemWellnessCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(int position) {
            // Only the pages ViewPager2 keeps alive are decoded, so the eleven images stay cheap.
            binding.wellnessImage.setImageResource(cards[position]);
            itemView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            itemView.setContentDescription(itemView.getContext().getString(
                    R.string.patient_wellness_accessible_card,
                    itemView.getContext().getString(WellnessCards.description(position)),
                    itemView.getContext().getString(R.string.patient_wellness_card_description, position + 1, cards.length)));
        }
    }
}
