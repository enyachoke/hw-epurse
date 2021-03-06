package ePurse;

import javacard.framework.*;
import javacard.security.KeyBuilder;
import javacard.security.RSAPrivateKey;
import javacard.security.RSAPublicKey;
import javacard.security.Signature;
import javacardx.crypto.Cipher;

import javax.xml.bind.DatatypeConverter;

/**
 * @noinspection ClassNamePrefixedWithPackageName, ImplicitCallToSuper, MethodOverridesStaticMethodOfSuperclass, ResultOfObjectAllocationIgnored
 */
public class Epurse extends Applet implements ISO7816 {

    final static short SW_VERIFICATION_FAILED = 0x6300;
    final static short SW_PIN_VERIFICATION_REQUIRED = 0x6301;
    final static short SW_TERMINAL_VERIFICATION_FAILED = 0x6302;
    final static short SW_CARD_BLOCKED = 0x6303;
    /**
     * wrong terminal nonce
     */
    final static short SW_WRONG_NONCE = 0x6304;
    final static short SW_NO_MORE_PIN_ATTEMPTS = 0x6305;
    /**
     * Instruction bytes
     */
    private final static byte EPURSE_CLA = (byte) 0xba;
    private final static byte PERSONALIZATION_BACKEND_KEY = (byte) 0x20;
    private final static byte PERSONALIZATION_HI = (byte) 0x30;
    private final static byte PERSONALIZATION_DATES = (byte) 0x31;
    private final static byte PERSONALIZATION_NEW_PIN = (byte) 0x32;
    private final static byte DECOMMISSIONING_HI = (byte) 0x33;
    private final static byte DECOMMISSIONING_CLEAR = (byte) 0x34;
    private final static byte RELOADING_HI = (byte) 0x35;
    private final static byte RELOADING_UPDATE = (byte) 0x36;
    private final static byte CREDIT_HI = (byte) 0x37;
    private final static byte CREDIT_COMMIT_PIN = (byte) 0x38;
    private final static byte CREDIT_COMMIT_NO_PIN = (byte) 0x39;
    private final static byte CREDIT_NEW_BALANCE = (byte) 0x40;
    private final static byte VERIFICATION_HI = (byte) 0x41;
    private final static byte VERIFICATION_V = (byte) 0x42;
    private final static byte VERIFICATION_S = (byte) 0x47;
    private final static byte KEYPAIR_PRIVATE = (byte) 0x43;
    private final static byte KEYPAIR_PRIVATE_RSA = (byte) 0x45;
    private final static byte DECRYPTION_KEY = (byte) 0x44;
    private final static byte SELECT = (byte) 0xA4;
    private final static short UNKNOWN_INSTRUCTION_ERROR = (short) 1;
    /**
     * State bytes
     */
    private final static byte STATE_RAW = 0;
    //private final static byte STATE_INITIALIZED = 1;
    private final static byte STATE_PERSONALIZED = 1;
    private final static byte STATE_DECOMMISSIONED = 2;

    /**
     * State bytes for session
     */
    private final static byte TERMINAL_NO_AUTH = 0;
    private final static byte TERMINAL_AUTH = 1;
    private final static byte PIN_LENGTH = (byte) 4;
    private final static byte ID_LENGTH = (byte) 2;
    private final static byte NONCE_LENGTH = (byte) 2;
    private final static byte AMOUNT_LENGTH = (byte) 2;
    private final static byte DATE_LENGTH = (byte) 8;
    private final static short MODULUS_LENGTH = 129;
    private final static short PRIVATE_EXPONENT_LENGTH = 128;
    private final static byte PUBLIC_EXPONENT_LENGTH = 3;
    Cipher cipher;
    /**
     * Ram volatile variables
     */
    byte[] lastNonce;
    /**
     * The communication state (auth or not)
     */
    byte[] sessionStatus;
    /**
     * Transient buffer
     */
    private byte[] transientBuffer;
    private byte[] headerBuffer;
    /**
     * Cryptographic primitives
     */
    //private Signature signingKey;

    private Signature signature;
    private RSAPublicKey pubKey;
    private RSAPrivateKey privKey;
    private RSAPublicKey backEndKey;
    private RSAPublicKey terminalKey;
    /**
     * PIN primitives
     */
    private OwnerPIN pin;
    /**
     * Eprom persisten variables
     */
    private byte[] id = new byte[2];
    private byte[] date = new byte[4];
    private byte[] expirationDate = new byte[4];
    private byte[] balance = new byte[2];
    /**
     * The applet state (RAW, PERSONALIZED or DECOMMISSIONED).
     */
    private byte status;
    private byte[] isPinChecked;
    private byte[] amount; //= new byte[2];

    /**
     * Constructor
     */
    public Epurse() {

        transientBuffer = JCSystem.makeTransientByteArray((short) 256, JCSystem.CLEAR_ON_RESET);
        headerBuffer = JCSystem.makeTransientByteArray((short) 5, JCSystem.CLEAR_ON_RESET);
        sessionStatus = JCSystem.makeTransientByteArray((short) 1, JCSystem.CLEAR_ON_RESET);
        lastNonce = JCSystem.makeTransientByteArray((short) 2, JCSystem.CLEAR_ON_RESET);
        amount = JCSystem.makeTransientByteArray((short) 2, JCSystem.CLEAR_ON_RESET);
        isPinChecked = JCSystem.makeTransientByteArray((short) 1, JCSystem.CLEAR_ON_RESET);

        pubKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_1024, false);
        privKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_1024, false);
        backEndKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_1024, false);
        terminalKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_1024, false);

        cipher = Cipher.getInstance(Cipher.ALG_RSA_PKCS1, false);
        pin = new OwnerPIN((byte) 3, (byte) 4);

        status = STATE_RAW;

        register();
    }

    /**
     * Installs the applet
     *
     * @param bArray
     * @param bOffset
     * @param bLength
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new Epurse();
    }

    public boolean select() {
        // Check whether there still some pin tries (max 3)
        if (pin.getTriesRemaining() > 0) return true;
        else return false;
    }

    /**
     * @param number the number to increment
     * @param offset from which index it should write the random number to the transcient buffer
     */
    private void incrementNumberAndStore(short number, short offset) {
        number += 1;
        transientBuffer[offset] = (byte) (number >> 8);
        transientBuffer[(short) (offset + (short) 1)] = (byte) number;
    }

    /**
     * Big endian
     *
     * @param msb    The most significant byte of the short
     * @param lsb    The least significant byte of the short
     * @param offset from which index it should write the random number to the transcient buffer
     */
    private void incrementNumberAndStore(byte msb, byte lsb, short offset) {

        short number = Util.makeShort(msb, lsb);
        number += 1;
        transientBuffer[offset] = (byte) (number >> 8);
        transientBuffer[(short) (offset + (short) 1)] = (byte) number;
        lastNonce[0] = transientBuffer[offset];
        lastNonce[1] = transientBuffer[(short) (offset + (short) 1)];
    }

    /**
     * Increment and also check whether is the right one
     *
     * @param msb    The most significant byte of the short
     * @param lsb    The least significant byte of the short
     * @param offset from which index it should write the random number to the transcient buffer
     */
    private void incrementNumberStoreAndCheck(byte msb, byte lsb, short offset, short increment) {

        short lastNumber = Util.makeShort(lastNonce[0], lastNonce[1]);
        short number = Util.makeShort(msb, lsb);

        if ((short) (lastNumber + increment) != number) ISOException.throwIt(SW_WRONG_NONCE);

        number += 1;
        transientBuffer[offset] = (byte) (number >> 8);
        transientBuffer[(short) (offset + (short) 1)] = (byte) number;

        lastNonce[0] = transientBuffer[offset];
        lastNonce[1] = transientBuffer[(short) (offset + (short) 1)];
    }


    /**
     * Sign a payload starting from the offset with the length of the payload
     *
     * @param source       The data to sign
     * @param sourceOffset The offset
     * @param sourceLength The length
     * @param dest         The destination
     * @param destOffset   The destination offset
     */
    private short sign(byte[] source, short sourceOffset, short sourceLength, byte[] dest, short destOffset) {
        signature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        signature.init(privKey, Signature.MODE_SIGN);
        return signature.sign(source, sourceOffset, sourceLength, dest, destOffset);
    }

    /**
     * Verify a payload starting from the offset with the length of the payload
     *
     * @param source      The data to sign
     * @param plainOffset The offset
     * @param plainLength The length
     * @param signOffset  The signature
     * @param signLength  The signature length
     * @param sk          The public key to use
     */
    private boolean verify(byte[] source, short plainOffset, short plainLength, byte[] signSource, short signOffset, short signLength, RSAPublicKey sk) {
        signature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        signature.init(sk, Signature.MODE_VERIFY);
        return signature.verify(source, plainOffset, plainLength, signSource, signOffset, signLength);
    }


    /**
     * @noinspection Process the apdu
     */
    public void process(APDU apdu) {

        if (selectingApplet()) {
            return;
        }

        // Store APDU header in global transient array
        Util.arrayCopy(apdu.getBuffer(), (short) 0, headerBuffer, (short) 0, (short) 5);

        // Check whether you are in the personalization state
        if (status == STATE_RAW) {
            switch (headerBuffer[OFFSET_INS]) {

                // Personalization APDUs:
                case PERSONALIZATION_BACKEND_KEY: {
                    saveBackendKey(apdu);
                    break;
                }
                case PERSONALIZATION_HI:
                    processPersonalizationHi(apdu);
                    break;
                case PERSONALIZATION_DATES:
                    processPersonalizationDates(apdu);
                    break;
                case PERSONALIZATION_NEW_PIN:
                    setPIN(apdu);
                    status = STATE_PERSONALIZED;
                    break;
                default:
                    throw new ISOException(SW_INS_NOT_SUPPORTED);
            }

        } else if (status == STATE_PERSONALIZED) {

            // Check whether the terminal auth is verified
            if (sessionStatus[0] == TERMINAL_NO_AUTH) {
                switch (headerBuffer[OFFSET_INS]) {
                    //Verification APDUs:
                    case VERIFICATION_HI:
                        processVerificationHi(apdu);
                        break;
                    case VERIFICATION_V:
                        processVerificationV(apdu);
                        break;
                    case VERIFICATION_S:
                        processVerificationSignature(apdu);
                        // Safe state term verified
                        sessionStatus[0] = TERMINAL_AUTH;
                        break;
                    default:
                        throw new ISOException(SW_INS_NOT_SUPPORTED);

                }

            } else if (sessionStatus[0] == TERMINAL_AUTH) {
                // Handle instructions
                switch (headerBuffer[OFFSET_INS]) {

                    //Decommissioning APDUs:
                    case DECOMMISSIONING_HI:
                        sendHiMessage(apdu);
                        break;
                    case DECOMMISSIONING_CLEAR:
                        processDecommissioningClear(apdu);
                        break;

                    //Reloading APDUs:
                    case RELOADING_HI:
                        sendHiMessage(apdu);
                        break;
                    case RELOADING_UPDATE:
                        processReloadingUpdate(apdu);
                        break;

                    //Crediting APDUs:
                    case CREDIT_HI:
                        sendHiMessage(apdu);
                        break;
                    case CREDIT_COMMIT_PIN:
                        processCommitPaymentPIN(apdu);
                        break;
                    case CREDIT_COMMIT_NO_PIN:
                        processCommitPayment(apdu);
                        break;
                    default:
                        throw new ISOException(SW_INS_NOT_SUPPORTED);
                }

            } else ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        } else ISOException.throwIt(SW_CARD_BLOCKED);
    }


    private void processVerificationHi(APDU apdu) {
        //Increment the received number
        short datalength = (short) headerBuffer[OFFSET_LC];
        readBuffer(apdu, transientBuffer, (short) 0, datalength);

        incrementNumberAndStore(transientBuffer[0], transientBuffer[1], (short) 0);

        //Send ID, we assume that the card is already personalized
        Util.arrayCopy(id, (short) 0, transientBuffer, (short) 2, (short) 2);
        datalength = (short) (datalength + 2);

        short signatureSize = sign(transientBuffer, (short) 0, datalength, transientBuffer, datalength);

        //Send the response
        apdu.setOutgoing();
        apdu.setOutgoingLength((short) (datalength + signatureSize));
        apdu.sendBytesLong(transientBuffer, (short) 0, (short) (datalength + signatureSize));

    }

    /**
     * Save temporarily the public key of the terminal
     *
     * @param apdu
     */
    private void processVerificationV(APDU apdu) {
        short datalength = (short) (headerBuffer[OFFSET_LC] & 0x00FF);
        readBuffer(apdu, transientBuffer, (short) 0, datalength);

        short exponentLength = (short) (headerBuffer[OFFSET_P1] & 0x00FF);
        short modulusLength = (short) (headerBuffer[OFFSET_P2] & 0x00FF);

        terminalKey.setExponent(transientBuffer, (short) 2, exponentLength);
        terminalKey.setModulus(transientBuffer, (short) (2 + exponentLength), modulusLength);

        lastNonce[0] = transientBuffer[0];
        lastNonce[1] = transientBuffer[1];
    }

    /**
     * Verify terminal key with the Backend signature
     *
     * @param apdu
     */
    private void processVerificationSignature(APDU apdu) {

        short datalength = (short) (headerBuffer[OFFSET_LC] & 0x00FF);
        readBuffer(apdu, transientBuffer, (short) 0, datalength);

        // Get teminal key data stored
        byte[] bytesTermKeyStored = new byte[(MODULUS_LENGTH + PUBLIC_EXPONENT_LENGTH) * 2 + 2];
        Util.arrayCopy(lastNonce, (short) 0, bytesTermKeyStored, (short) 0, (short) 2);
        pubKey.getExponent(bytesTermKeyStored, (short) (NONCE_LENGTH));
        pubKey.getModulus(bytesTermKeyStored, (short) (NONCE_LENGTH + PUBLIC_EXPONENT_LENGTH));
        terminalKey.getExponent(bytesTermKeyStored, ((short) (NONCE_LENGTH + PUBLIC_EXPONENT_LENGTH + MODULUS_LENGTH)));
        terminalKey.getModulus(bytesTermKeyStored, ((short) (NONCE_LENGTH + PUBLIC_EXPONENT_LENGTH * 2 + MODULUS_LENGTH)));

        //Todo: we wanted to add a signature check but only in this case we assume that the terminal signature verification satisfies


        // Build original signed message with the public key of the card [NONCE,PKC, PKT]
//         Util.arrayCopy(bytesTermKeyStored, (short) 0, transientBuffer, (short) NONCE_LENGTH, (short) bytesTermKeyStored.length);
//        // TODO nonce + 1 or +2 ??
//
//        // TODO: verify [NONCE,PKC, PKT] with received sign
//
//        // FIXME: 6f00 in the next line ...
//        boolean isVerified = verify(bytesTermKeyStored, (short) 0, ((short) bytesTermKeyStored.length), transientBuffer, (short) 0, (short) 128, backEndKey);
//        if (!isVerified) ISOException.throwIt(SW_TERMINAL_VERIFICATION_FAILED);

        incrementNumberAndStore(lastNonce[0], lastNonce[1], (short) 0);
    }

    /**
     * Store the public key of the Backend within the personalization phase
     *
     * @param apdu
     */
    private void saveBackendKey(APDU apdu) {
        short datalength = (short) (headerBuffer[OFFSET_LC] & 0x00FF);
        readBuffer(apdu, transientBuffer, (short) 0, datalength);

        short exponentLength = (short) (headerBuffer[OFFSET_P1] & 0x00FF);
        short modulusLength = (short) (headerBuffer[OFFSET_P2] & 0x00FF);

        backEndKey.setExponent(transientBuffer, (short) 0, exponentLength);
        backEndKey.setModulus(transientBuffer, exponentLength, modulusLength);

    }

    /**
     * Read apdu buffer and send back [nonce+1, id] SKC
     *
     * @param apdu
     */
    private void sendHiMessage(APDU apdu) {
        //Increment the received number
        short datalength = (short) (headerBuffer[OFFSET_LC] & 0x00FF);
        readBuffer(apdu, transientBuffer, (short) 0, datalength);

        // Verify with received sign
        boolean isVerified = verify(transientBuffer, (short) 0, (short) 2, transientBuffer, (short) 2, (short) 128, terminalKey);
        // If signature verification not verified throw exception

        if (!isVerified) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        incrementNumberAndStore(transientBuffer[0], transientBuffer[1], (short) 0);

        //Send ID, we assume that the card is already personalized
        Util.arrayCopy(id, (short) 0, transientBuffer, (short) 2, (short) 2);
        datalength = (short) (4);

        short signatureSize = sign(transientBuffer, (short) 0, datalength, transientBuffer, datalength);

        //Send the response
        apdu.setOutgoing();
        apdu.setOutgoingLength((short) (datalength + signatureSize));
        apdu.sendBytesLong(transientBuffer, (short) 0, (short) (datalength + signatureSize));

    }

    /**
     * Read apdu buffer that contains the public and the private keys of the card. This keys are split it in 4 apdus
     *
     * @param apdu
     */
    private void processPersonalizationHi(APDU apdu) {

        byte keyValue = headerBuffer[OFFSET_P1];
        switch (keyValue) {
            case 0:
                readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                privKey.setModulus(transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                break;
            case 1:
                readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                privKey.setExponent(transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                break;
            case 2:
                readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                pubKey.setModulus(transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                break;
            case 3:
                readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                pubKey.setExponent(transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
                break;
        }
    }

    private void processPersonalizationDates(APDU apdu) {
        readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
        Util.arrayCopy(transientBuffer, (short) (0), id, (short) 0, (short) 2);
        Util.arrayCopy(transientBuffer, (short) (2), date, (short) 0, (short) 4);
    }

    private void setPIN(APDU apdu) {
        if (headerBuffer[OFFSET_LC] > PIN_LENGTH)
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        else
            readBuffer(apdu, transientBuffer, (short) 0, headerBuffer[OFFSET_LC]);
        pin.update(transientBuffer, (short) 0, (byte) 4);

    }

    private void processCommitPaymentPIN(APDU apdu) {
        readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
        if (headerBuffer[ISO7816.OFFSET_P1] == 0) {
            // Verify nonce, increment and save it to send in the second apdu
            lastNonce[0] = transientBuffer[0];
            lastNonce[1] = transientBuffer[1];

            // amount is what you should pay and balance is what you have aftewards
            //Received [nonce + amount + balance encripted(signed(pin))] 6+128

            //Initialize cipher
            byte[] pinBytes = new byte[4];
            cipher.init(privKey, Cipher.MODE_DECRYPT);
            cipher.doFinal(transientBuffer, (short) 4, (short) 128, pinBytes, (short) 0);

            // Verify pin, first check if there remain any tries
            if (pin.getTriesRemaining() <= 0x00) {
                ISOException.throwIt(SW_NO_MORE_PIN_ATTEMPTS);
            }

            if (!pin.check(pinBytes, (short) 0, PIN_LENGTH))
                ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

            isPinChecked[0] = 0x56;

            //Get new balance and amount
            Util.arrayCopy(transientBuffer, (short) 2, amount, (short) 0, (short) 2);

        } else if (headerBuffer[ISO7816.OFFSET_P1] == 1) {
            //Adding the if pin validated check yielded an error
            if (isPinChecked[0] == 0x56) {
                pay(apdu);
            } else {
                ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
            }


        } else ISOException.throwIt(SW_COMMAND_NOT_ALLOWED);

    }

    private void processCommitPayment(APDU apdu) {
        readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));
        byte[] payload = new byte[4];

        // Check nonce is incremented +2
//        ISOException.throwIt(Util.makeShort(transientBuffer[0], transientBuffer[1]));
        incrementNumberStoreAndCheck(transientBuffer[0], transientBuffer[1], (short) 0, (short) 2);

        // Get amount
        Util.arrayCopy(transientBuffer, (short) 2, amount, (short) 0, (short) 2);

        // Check we have enough balance
        short sBalance = Util.makeShort(balance[0], balance[1]);
        short sAmount = Util.makeShort(amount[0], amount[1]);
        if ((short) (sBalance - sAmount) < (short) 0) ISOException.throwIt(SW_DATA_INVALID);

        // Copi nonde id and amount to sing
        //Util.arrayCopy(lastNonce, (short) 0, transientBuffer, (short) 0, (short) 2);
        Util.arrayCopy(id, (short) 0, transientBuffer, (short) 2, (short) 2);
        Util.arrayCopy(amount, (short) 0, transientBuffer, (short) 4, (short) 2);

        // We sing wit offset 2 to prevent the overridign of the nonce
        short signedResponseLength = sign(transientBuffer, (short) 0, (short) 6, transientBuffer, (short) 6);

        //Send the response
        apdu.setOutgoing();
        apdu.setOutgoingLength((short) (6 + signedResponseLength));
        apdu.sendBytesLong(transientBuffer, (short) 0, (short) (6 + signedResponseLength));
        //pay(apdu);
    }

    // Commit the payment, done after verify the pin (CREDIT_COMMIT_PIN) or pay without pin (CREDIT_COMMIT_NO_PIN)
    private void pay(APDU apdu) {

        //Util.arrayCopy(transientBuffer, (short) 2, amount, (short) 0, (short) 2);
        // in the second apdu we receive the nonec amount balance signed
        byte[] payload = new byte[4];

        Util.arrayCopy(lastNonce, (short) 0, payload, (short) 0, (short) 2);
        Util.arrayCopy(amount, (short) 0, payload, (short) 2, (short) 2);
        //Util.arrayCopy(balance, (short) 0, payload, (short) 4, (short) 2);
        //datalength = (short) (datalength + 2);

        boolean payloadVerified = verify(payload, (short) 0, (short) 4, transientBuffer, (short) 0, (short) 128, terminalKey);
        if (!payloadVerified) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        incrementNumberAndStore(lastNonce[0], lastNonce[1], (short) 0);
        short sBalance = Util.makeShort(balance[0], balance[1]);
        short sAmount = Util.makeShort(amount[0], amount[1]);
        if ((short) (sBalance - sAmount) < (short) 0) ISOException.throwIt(SW_DATA_INVALID);

        // Copi nonde id and amount to sing
        Util.arrayCopy(lastNonce, (short) 0, transientBuffer, (short) 0, (short) 2);
        Util.arrayCopy(id, (short) 0, transientBuffer, (short) 2, (short) 2);
        Util.arrayCopy(amount, (short) 0, transientBuffer, (short) 4, (short) 2);

        // We sing wit offset 2 to prevent the overridign of the nonce
        short signedResponseLength = sign(transientBuffer, (short) 0, (short) 6, transientBuffer, (short) 6);

//            ISOException.throwIt(signedResponseLength);

        //Send the response
        apdu.setOutgoing();
        apdu.setOutgoingLength((short) (6 + signedResponseLength));
        apdu.sendBytesLong(transientBuffer, (short) 0, (short) (6 + signedResponseLength));


    }


    private void processDecommissioningClear(APDU apdu) {
        readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));

        // Verify with received signature
        boolean isVerified = verify(transientBuffer, (short) 0, (short) 4, transientBuffer, (short) 4, (short) 128, backEndKey); // Changed to bekey
        // If signature verification not verified throw exception
        if (!isVerified) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        // Verify id
        if (Util.arrayCompare(id, (short) 0, transientBuffer, (short) 2, (short) 2) != 0x00)
            ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        // Verify nonce
        incrementNumberStoreAndCheck(transientBuffer[0], transientBuffer[1], (short) 0, (short) 1);

        // We sing wit offset 2 to prevent the overridign of the nonce
        short signedResponseLength = sign(transientBuffer, (short) 0, (short) 2, transientBuffer, (short) 2);

        // Block the card before send the response
        status = STATE_DECOMMISSIONED;

        //Send the response
        apdu.setOutgoing();
        apdu.setOutgoingLength((short) (2 + signedResponseLength));
        apdu.sendBytesLong(transientBuffer, (short) 0, (short) (2 + signedResponseLength));


    }

    /**
     * Process message [nonce, id, amount] SKT
     *
     * @param apdu
     */
    private void processReloadingUpdate(APDU apdu) {

        readBuffer(apdu, transientBuffer, (short) 0, (short) (headerBuffer[OFFSET_LC] & 0x00FF));

        // Verify with received sign
        short payloadLength = NONCE_LENGTH + ID_LENGTH + AMOUNT_LENGTH;
        boolean isVerified = verify(transientBuffer, (short) 0, payloadLength, transientBuffer, payloadLength, (short) 128, terminalKey);
        // If signature verification not verified throw exception
        if (!isVerified) ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);

        incrementNumberStoreAndCheck(transientBuffer[0], transientBuffer[1], (short) 0, (short) 2);

        // Verify id
        if (Util.arrayCompare(id, (short) 0, transientBuffer, (short) 2, (short) 2) != 0x00)
            ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);


        // Store new amount
        Util.arrayCopy(transientBuffer, (short) (NONCE_LENGTH + ID_LENGTH), balance, (short) 0, AMOUNT_LENGTH);
    }


    /**
     * Read apdu buffer and store into a different array
     *
     * @param apdu   The apdu
     * @param dest   The destination array
     * @param offset The offset within the destination array
     * @param length The length
     */
    private void readBuffer(APDU apdu, byte[] dest, short offset,
                            short length) {
        byte[] buf = apdu.getBuffer();
        short readCount = apdu.setIncomingAndReceive();
        short i = 0;
        Util.arrayCopy(buf, OFFSET_CDATA, dest, offset, readCount);
        while ((short) (i + readCount) < length) {
            i += readCount;
            offset += readCount;
            readCount = (short) apdu.receiveBytes(OFFSET_CDATA);
            Util.arrayCopy(buf, OFFSET_CDATA, dest, offset, readCount);
        }
    }

    public void deselect() {
        pin.reset();
    }

}