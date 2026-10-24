package com.indushostel.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FinancialRulesTest {
    private static BigDecimal bd(String value){return new BigDecimal(value);}

    @Test void partialPaymentsReduceBalanceWithoutFloatingPointRounding(){
        BigDecimal paid=FinancialRules.applyApprovedPayment(bd("18000.00"),bd("7250.00"),bd("3500.25"));
        assertEquals(bd("7249.75"),FinancialRules.remaining(bd("18000.00"),paid));
    }

    @Test void paymentCannotExceedTheOutstandingBalance(){
        assertThrows(IllegalArgumentException.class,()->FinancialRules.applyApprovedPayment(bd("5000"),bd("4500"),bd("500.01")));
    }

    @Test void onlyApprovedPaymentsCountAsVerifiedCollections(){
        var entries=List.of(new FinancialRules.PaymentAmount(bd("4000"),FinancialRules.PaymentState.APPROVED),
            new FinancialRules.PaymentAmount(bd("2500"),FinancialRules.PaymentState.PENDING),
            new FinancialRules.PaymentAmount(bd("900"),FinancialRules.PaymentState.REJECTED),
            new FinancialRules.PaymentAmount(bd("300"),FinancialRules.PaymentState.REVERSED));
        assertEquals(bd("4000"),FinancialRules.verifiedCollections(entries));
    }

    @Test void pendingSubmissionDoesNotEraseAnOutstandingBalance(){
        assertEquals("PENDING_VERIFICATION",FinancialRules.invoiceStatus(bd("10000"),bd("0"),LocalDate.parse("2026-11-05"),true,LocalDate.parse("2026-11-02")));
        assertEquals("OVERDUE",FinancialRules.invoiceStatus(bd("10000"),bd("0"),LocalDate.parse("2026-10-01"),true,LocalDate.parse("2026-11-02")));
    }

    @Test void ownerWithdrawalsAreNotPartOfOperatingPosition(){
        assertEquals(bd("15000"),FinancialRules.operatingPosition(bd("42000"),bd("27000")));
    }
}
