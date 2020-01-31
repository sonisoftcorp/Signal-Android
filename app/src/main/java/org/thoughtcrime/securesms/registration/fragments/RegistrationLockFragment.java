package org.thoughtcrime.securesms.registration.fragments;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.navigation.Navigation;

import com.dd.CircularProgressButton;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.lock.v2.KbsKeyboardType;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.registration.service.CodeVerificationRequest;
import org.thoughtcrime.securesms.registration.service.RegistrationService;
import org.thoughtcrime.securesms.registration.viewmodel.RegistrationViewModel;
import org.whispersystems.signalservice.internal.contacts.entities.TokenResponse;

import java.util.concurrent.TimeUnit;

public final class RegistrationLockFragment extends BaseRegistrationFragment {

  private static final String TAG = Log.tag(RegistrationLockFragment.class);

  private EditText               pinEntry;
  private CircularProgressButton pinButton;
  private TextView               errorLabel;
  private TextView               keyboardToggle;
  private long                   timeRemaining;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_registration_lock, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    setDebugLogSubmitMultiTapView(view.findViewById(R.id.kbs_lock_pin_title));

    pinEntry       = view.findViewById(R.id.kbs_lock_pin_input);
    pinButton      = view.findViewById(R.id.kbs_lock_pin_confirm);
    errorLabel     = view.findViewById(R.id.kbs_lock_pin_input_label);
    keyboardToggle = view.findViewById(R.id.kbs_lock_keyboard_toggle);

    View pinForgotButton = view.findViewById(R.id.kbs_lock_forgot_pin);

    timeRemaining = RegistrationLockFragmentArgs.fromBundle(requireArguments()).getTimeRemaining();

    pinForgotButton.setOnClickListener(v -> handleForgottenPin(timeRemaining));

    pinEntry.setImeOptions(EditorInfo.IME_ACTION_DONE);
    pinEntry.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_DONE) {
        hideKeyboard(requireContext(), v);
        handlePinEntry();
        return true;
      }
      return false;
    });

    pinButton.setOnClickListener((v) -> {
      hideKeyboard(requireContext(), pinEntry);
      handlePinEntry();
    });

    keyboardToggle.setOnClickListener((v) -> {
      KbsKeyboardType keyboardType = getPinEntryKeyboardType();

      updateKeyboard(keyboardType);
      keyboardToggle.setText(resolveKeyboardToggleText(keyboardType));
    });

    getModel().getTimeRemaining()
              .observe(getViewLifecycleOwner(), t -> timeRemaining = t);
  }

  private KbsKeyboardType getPinEntryKeyboardType() {
    boolean isNumeric = (pinEntry.getImeOptions() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER;

    return isNumeric ? KbsKeyboardType.NUMERIC : KbsKeyboardType.ALPHA_NUMERIC;
  }

  private void handlePinEntry() {
    final String pin = pinEntry.getText().toString();

    if (TextUtils.isEmpty(pin) || TextUtils.isEmpty(pin.replace(" ", ""))) {
      Toast.makeText(requireContext(), R.string.RegistrationActivity_you_must_enter_your_registration_lock_PIN, Toast.LENGTH_LONG).show();
      return;
    }

    RegistrationViewModel model                   = getModel();
    RegistrationService   registrationService     = RegistrationService.getInstance(model.getNumber().getE164Number(), model.getRegistrationSecret());
    TokenResponse         tokenResponse           = model.getKeyBackupCurrentToken();
    String                basicStorageCredentials = model.getBasicStorageCredentials();

    setSpinning(pinButton);

    registrationService.verifyAccount(requireActivity(),
                                      model.getFcmToken(),
                                      model.getTextCodeEntered(),
                                      pin,
                                      basicStorageCredentials,
                                      tokenResponse,

      new CodeVerificationRequest.VerifyCallback() {

        @Override
        public void onSuccessfulRegistration() {
          cancelSpinning(pinButton);
          SignalStore.kbsValues().setKeyboardType(getPinEntryKeyboardType());

          Navigation.findNavController(requireView()).navigate(RegistrationLockFragmentDirections.actionSuccessfulRegistration());
        }

        @Override
        public void onV1RegistrationLockPinRequiredOrIncorrect(long timeRemaining) {
          getModel().setTimeRemaining(timeRemaining);

          cancelSpinning(pinButton);
          pinEntry.getText().clear();

          errorLabel.setText(R.string.KbsLockFragment__incorrect_pin);
        }

        @Override
        public void onKbsRegistrationLockPinRequired(long timeRemaining, @NonNull TokenResponse kbsTokenResponse, @NonNull String kbsStorageCredentials) {
          throw new AssertionError("Not expected after a pin guess");
        }

        @Override
        public void onIncorrectKbsRegistrationLockPin(@NonNull TokenResponse tokenResponse) {
          cancelSpinning(pinButton);
          pinEntry.getText().clear();

          model.setKeyBackupCurrentToken(tokenResponse);

          int triesRemaining = tokenResponse.getTries();

          if (triesRemaining == 0) {
            Log.w(TAG, "Account locked. User out of attempts on KBS.");
            lockAccount(timeRemaining);
            return;
          }

          if (triesRemaining == 3) {
            long daysRemaining = getLockoutDays(timeRemaining);

            new AlertDialog.Builder(requireContext())
                           .setTitle(R.string.KbsLockFragment__incorrect_pin)
                           .setMessage(getString(R.string.KbsLockFragment__you_have_d_attempts_remaining, triesRemaining, daysRemaining, daysRemaining))
                           .setPositiveButton(android.R.string.ok, null)
                           .show();
          }

          if (triesRemaining > 5) {
            errorLabel.setText(R.string.KbsLockFragment__incorrect_pin_try_again);
          } else {
            errorLabel.setText(getString(R.string.KbsLockFragment__incorrect_pin_d_attempts_remaining, triesRemaining));
          }
        }

        @Override
        public void onRateLimited() {
          cancelSpinning(pinButton);

          new AlertDialog.Builder(requireContext())
                         .setTitle(R.string.RegistrationActivity_too_many_attempts)
                         .setMessage(R.string.RegistrationActivity_you_have_made_too_many_incorrect_registration_lock_pin_attempts_please_try_again_in_a_day)
                         .setPositiveButton(android.R.string.ok, null)
                         .show();
        }

        @Override
        public void onKbsAccountLocked(long timeRemaining) {
          getModel().setTimeRemaining(timeRemaining);

          lockAccount(timeRemaining);
        }

        @Override
        public void onError() {
          cancelSpinning(pinButton);

          Toast.makeText(requireContext(), R.string.RegistrationActivity_error_connecting_to_service, Toast.LENGTH_LONG).show();
        }
      });
  }

  private void handleForgottenPin(long timeRemainingMs) {
    new AlertDialog.Builder(requireContext())
                   .setTitle(R.string.KbsLockFragment__forgot_your_pin)
                   .setMessage(getString(R.string.KbsLockFragment__for_your_privacy_and_security_there_is_no_way_to_recover, getLockoutDays(timeRemainingMs)))
                   .setPositiveButton(android.R.string.ok, null)
                   .show();
  }

  private static long getLockoutDays(long timeRemainingMs) {
    return TimeUnit.MILLISECONDS.toDays(timeRemainingMs) + 1;
  }

  private void lockAccount(long timeRemaining) {
    RegistrationLockFragmentDirections.ActionAccountLocked action = RegistrationLockFragmentDirections.actionAccountLocked(timeRemaining);

    Navigation.findNavController(requireView()).navigate(action);
  }

  private void updateKeyboard(@NonNull KbsKeyboardType keyboard) {
    boolean isAlphaNumeric = keyboard == KbsKeyboardType.ALPHA_NUMERIC;

    pinEntry.setInputType(isAlphaNumeric ? InputType.TYPE_CLASS_TEXT   | InputType.TYPE_TEXT_VARIATION_PASSWORD
                                         : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
  }

  private @StringRes static int resolveKeyboardToggleText(@NonNull KbsKeyboardType keyboard) {
    if (keyboard == KbsKeyboardType.ALPHA_NUMERIC) {
      return R.string.KbsLockFragment__enter_alphanumeric_pin;
    } else {
      return R.string.KbsLockFragment__enter_numeric_pin;
    }
  }
}