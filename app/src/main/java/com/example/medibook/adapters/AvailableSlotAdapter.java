package com.example.medibook.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemAvailableSlotBinding;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.DhakaDateTime;

import java.util.ArrayList;
import java.util.List;

/** Single selection; rows are rendered only from server-confirmed inventory. */
public final class AvailableSlotAdapter extends RecyclerView.Adapter<AvailableSlotAdapter.Holder> {
    public interface Listener { void selected(InventorySlot slot); }
    private final Listener listener;
    private List<InventorySlot> slots = new ArrayList<>();
    private String selectedId;
    private boolean busy;
    public AvailableSlotAdapter(Listener listener) { this.listener = listener; }
    public void submit(List<InventorySlot> values) {
        int old = slots.size(); slots = new ArrayList<>(values); selectedId = null;
        if (old > 0) notifyItemRangeRemoved(0, old);
        if (!slots.isEmpty()) notifyItemRangeInserted(0, slots.size());
    }
    public void setBusy(boolean value) { busy = value; if (!slots.isEmpty()) notifyItemRangeChanged(0, slots.size()); }
    @Override public int getItemCount() { return slots.size(); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(ItemAvailableSlotBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        InventorySlot slot = slots.get(position);
        holder.binding.getRoot().setText(holder.itemView.getContext().getString(R.string.appointment_slot_label,
                DhakaDateTime.formatTime12Hour(slot.startMinute), DhakaDateTime.formatTime12Hour(slot.endMinute),
                com.example.medibook.utils.SlotSerials.label(slot.slotSerial, slot.startMinute, slot.endMinute)));
        holder.binding.getRoot().setChecked(slot.id.equals(selectedId)); holder.itemView.setEnabled(!busy);
        holder.itemView.setOnClickListener(view -> {
            if (busy) return;
            String previous = selectedId;
            selectedId = slot.id;
            // Only the old/new checked rows change; do not rebind every slot for one tap.
            for (int index = 0; index < slots.size(); index++) {
                String id = slots.get(index).id;
                if (id.equals(previous) || id.equals(selectedId)) notifyItemChanged(index);
            }
            listener.selected(slot);
        });
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ItemAvailableSlotBinding binding;
        Holder(ItemAvailableSlotBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
