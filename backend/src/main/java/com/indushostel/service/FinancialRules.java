package com.indushostel.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;

public final class FinancialRules {
    private FinancialRules() {}
    public enum PaymentState { PENDING, APPROVED, REJECTED, REVERSED }
    public record PaymentAmount(BigDecimal amount,PaymentState state) {}

    public static BigDecimal remaining(BigDecimal charge,BigDecimal approvedPaid) {
        requireNonNegative(charge,"Charge");requireNonNegative(approvedPaid,"Approved amount");
        if(approvedPaid.compareTo(charge)>0)throw new IllegalArgumentException("Approved payments cannot exceed the fee charge");
        return charge.subtract(approvedPaid);
    }

    public static BigDecimal applyApprovedPayment(BigDecimal charge,BigDecimal approvedPaid,BigDecimal submitted) {
        requirePositive(submitted,"Payment");
        BigDecimal remaining=remaining(charge,approvedPaid);
        if(submitted.compareTo(remaining)>0)throw new IllegalArgumentException("Payment exceeds the remaining fee balance");
        return approvedPaid.add(submitted);
    }

    public static String invoiceStatus(BigDecimal charge,BigDecimal paid,LocalDate dueDate,boolean hasPending,LocalDate today) {
        BigDecimal balance=remaining(charge,paid);
        if(balance.signum()==0)return "PAID";
        if(dueDate.isBefore(today))return "OVERDUE";
        if(hasPending)return "PENDING_VERIFICATION";
        if(paid.signum()>0)return "PARTIALLY_PAID";
        return "UNPAID";
    }

    public static BigDecimal verifiedCollections(Collection<PaymentAmount> payments) {
        return payments.stream().filter(p->p.state()==PaymentState.APPROVED).map(PaymentAmount::amount)
            .peek(value->requireNonNegative(value,"Payment amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
    }

    public static BigDecimal operatingPosition(BigDecimal verifiedCollections,BigDecimal operatingExpenses) {
        requireNonNegative(verifiedCollections,"Verified collections");requireNonNegative(operatingExpenses,"Operating expenses");
        return verifiedCollections.subtract(operatingExpenses);
    }

    private static void requirePositive(BigDecimal value,String label) { if(value==null||value.signum()<=0)throw new IllegalArgumentException(label+" must be greater than zero"); }
    private static void requireNonNegative(BigDecimal value,String label) { if(value==null||value.signum()<0)throw new IllegalArgumentException(label+" cannot be negative"); }
}
