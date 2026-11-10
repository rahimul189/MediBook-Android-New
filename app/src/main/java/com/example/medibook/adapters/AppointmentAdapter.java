package com.example.medibook.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemAppointmentBinding;
import com.example.medibook.models.Appointment;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.DhakaDateTime;

import java.util.ArrayList;
import java.util.List;

public final class AppointmentAdapter extends RecyclerView.Adapter<AppointmentAdapter.Holder> {
    public interface Listener { void open(Appointment appointment); }
    private final Listener listener;
    private List<Appointment> entries = new ArrayList<>();
    private boolean admin;
    private long renderedAt;
    public AppointmentAdapter(Listener listener) { this.listener = listener; }
    public void submit(List<Appointment> values, boolean admin) {
        List<Appointment> next = new ArrayList<>(values);
        long now = System.currentTimeMillis();
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new AppointmentRowDiff(entries, next,
                this.admin, admin, renderedAt, now));
        entries = next; this.admin = admin; renderedAt = now;
        changes.dispatchUpdatesTo(this);
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(ItemAppointmentBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Appointment a = entries.get(position);
        holder.binding.title.setText(admin ? holder.itemView.getContext().getString(R.string.appointment_admin_row_title,
                a.getDoctorName(), a.getPatientName()) : a.getDoctorName());
        com.example.medibook.utils.AppointmentPresentation.bindStatus(holder.binding.status, a);
        holder.binding.summary.setText(holder.itemView.getContext().getString(R.string.appointment_card_schedule,
                AppointmentUi.date(a.getAppointmentDate().toDate().getTime()), DhakaDateTime.formatTime12Hour(a.getStartMinute()),
                DhakaDateTime.formatTime12Hour(a.getEndMinute())));
        com.example.medibook.utils.AppointmentPresentation.bindSlot(holder.binding.slot, a);
        holder.itemView.setContentDescription(holder.binding.title.getText() + ". " + holder.itemView.getContext().getString(R.string.appointment_row,
                AppointmentUi.date(a.getAppointmentDate().toDate().getTime()), DhakaDateTime.formatTime12Hour(a.getStartMinute()),
                DhakaDateTime.formatTime12Hour(a.getEndMinute()), AppointmentUi.status(holder.itemView.getContext(), a),
                com.example.medibook.utils.SlotSerials.label(a.getSlotSerial(), a.getStartMinute(), a.getEndMinute())));
        holder.itemView.setOnClickListener(view -> listener.open(a));
    }
    @Override public int getItemCount() { return entries.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final ItemAppointmentBinding binding;
        Holder(ItemAppointmentBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
