package com.indushostel.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class Requests {
    private Requests() {}
    public record Login(@Email @NotBlank String email, @NotBlank String password) {}
    public record PasswordResetRequest(@Email @NotBlank String email) {}
    public record PasswordResetConfirm(@NotBlank String token, @NotBlank @Size(min=12,max=100) String newPassword) {}
    public record UserStatus(@NotNull Boolean enabled) {}
    public record Room(@NotBlank @Size(max=40) String roomNumber, @Size(max=100) String section,
                       @Min(1) @Max(200) int capacity, @Size(max=1000) String notes) {}
    public record Student(@NotBlank @Size(max=40) String studentCode, @NotBlank @Size(max=160) String fullName,
                          @NotBlank @Size(max=40) String contact, @Email @NotBlank String email,
                          @NotBlank @Size(min=12,max=100) String initialPassword,
                          @NotNull @DecimalMin("0.00") @Digits(integer=10,fraction=2) BigDecimal monthlyFee,
                          @Min(1) @Max(28) int dueDay, @NotNull LocalDate registrationDate,
                          LocalDate checkInDate, Long roomId, @Size(max=20) String bedNumber,
                          @Size(max=160) String emergencyContact, @Size(max=500) String address,
                          @Size(max=1000) String notes) {}
    public record StudentUpdate(@NotBlank @Size(max=160) String fullName, @NotBlank @Size(max=40) String contact,
                                @NotNull @DecimalMin("0.00") @Digits(integer=10,fraction=2) BigDecimal monthlyFee,
                                @Min(1) @Max(28) int dueDay, @Size(max=160) String emergencyContact,
                                @Size(max=500) String address, @Size(max=1000) String notes) {}
    public record Assignment(@NotNull Long roomId, @NotBlank @Size(max=20) String bedNumber, @NotNull LocalDate date) {}
    public record Payment(@NotNull Long invoiceId, @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,
                          @NotNull LocalDate paymentDate, @Size(max=60) String method,
                          @Size(max=120) String reference, Long proofFileId,
                          @NotBlank @Size(max=80) String idempotencyKey) {}
    public record Rejection(@NotBlank @Size(max=500) String reason) {}
    public record Expense(@NotNull Long categoryId, @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,
                          @NotNull LocalDate expenseDate, @NotBlank @Size(max=500) String description,
                          @Size(max=160) String vendor, @Size(max=60) String paymentMethod, Long billFileId,
                          @NotBlank @Size(max=80) String idempotencyKey) {}
    public record Employee(@NotBlank @Size(max=40) String employeeCode, @NotBlank @Size(max=160) String fullName,
                           @NotBlank @Size(max=100) String title, @Size(max=40) String contact,
                           @NotNull LocalDate joiningDate, @NotNull @DecimalMin("0.00") BigDecimal salaryAmount,
                           @Size(max=500) String notes) {}
    public record SalaryPayment(@NotNull Long employeeId, @NotNull LocalDate salaryPeriod,
                                @NotNull @DecimalMin("0.00") BigDecimal amountDue,
                                @NotNull @DecimalMin("0.00") BigDecimal amountPaid,
                                LocalDate paymentDate, @Size(max=500) String notes,
                                @NotBlank @Size(max=80) String idempotencyKey) {}
    public record Advance(@NotNull Long employeeId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                          @NotNull LocalDate issuedDate, @NotBlank @Size(max=500) String reason,
                          @NotBlank @Size(max=80) String idempotencyKey) {}
    public record AdvanceRepayment(@NotNull @DecimalMin("0.01") BigDecimal amount,
                                   @NotNull LocalDate transactionDate, @Size(max=500) String note,
                                   @NotBlank @Size(max=80) String idempotencyKey) {}
    public record Withdrawal(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull LocalDate date,
                             @NotBlank @Size(max=500) String reason, @Size(max=60) String method,
                             @Size(max=500) String note,@NotBlank @Size(max=80) String idempotencyKey) {}
    public record BillSchedule(@NotBlank @Size(max=80) String category, @Size(max=160) String vendor,
                               @NotNull @DecimalMin("0.00") BigDecimal expectedAmount,
                               @NotBlank String frequency, @NotNull LocalDate nextDueDate,
                               @Size(max=500) String notes,@NotBlank @Size(max=80) String idempotencyKey) {}
    public record BillPayment(@NotBlank @Size(max=80) String category, @Size(max=160) String vendor,
                              @NotNull @DecimalMin("0.00") BigDecimal amount, @NotNull LocalDate dueDate,
                              LocalDate paymentDate, Long billFileId, @Size(max=500) String notes) {}
    public record Branding(@NotBlank @Size(max=160) String hostelName, @Email @Size(max=190) String ownerEmail, Long logoFileId) {}
    public record Account(@Email @NotBlank String email, @NotBlank @Size(max=160) String displayName,
                          @NotBlank String role, @NotBlank @Size(min=12,max=100) String initialPassword) {}
}
