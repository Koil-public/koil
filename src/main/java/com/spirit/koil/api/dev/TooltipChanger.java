package com.spirit.koil.api.dev;

import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TooltipChanger {
    public static ArrayList<Text> Main(ItemStack itemStack, ArrayList<Text> list) {
        ArrayList<Text> temp = new ArrayList<>();

        temp.add(Text.translatable("item.nbt_tags", itemStack.getNbt().getKeys().size()).formatted(Formatting.DARK_GRAY));
        int index = list.indexOf(temp.get(0));
        int indexInsertLocation = list.indexOf(temp.get(0));
        list.remove(index);

        String nbtList = String.valueOf(itemStack.getNbt());
        Pattern p = Pattern.compile("[{}:\"\\[\\],']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(nbtList);

        MutableText mutableText = Text.empty();
        mutableText.append(Text.literal("NBT: ").formatted(Formatting.DARK_GRAY));

        int lineStep = 50;
        Formatting stringColour = Formatting.GREEN;
        Formatting quotationColour = Formatting.WHITE;
        Formatting separationColour = Formatting.WHITE;
        Formatting integerColour = Formatting.GOLD;
        Formatting typeColour = Formatting.RED;
        Formatting fieldColour = Formatting.AQUA;
        Formatting lstringColour = Formatting.YELLOW;

        int lineLimit = lineStep;
        int removedCharters = 0;
        int lastIndex = 0;
        Boolean singleQuotationMark = Boolean.FALSE;
        Boolean lineAdded = Boolean.FALSE;
        String lastString = "";


        while (m.find()) {
            lineAdded = Boolean.FALSE;

            if (nbtList.charAt(m.start()) == '\'') {
                if (singleQuotationMark.equals(Boolean.FALSE)) {
                    mutableText.append(Text.literal(String.valueOf(nbtList.charAt(m.start()))).formatted(quotationColour));
                    singleQuotationMark = Boolean.TRUE;
                } else {
                    mutableText.append(Text.literal(nbtList.substring(lastIndex + 1, m.start())).formatted(stringColour));
                    mutableText.append(Text.literal(String.valueOf(nbtList.charAt(m.start()))).formatted(quotationColour));
                    singleQuotationMark = Boolean.FALSE;
                }
                lastString = String.valueOf(nbtList.charAt(m.start()));
                lastIndex = m.start();
            }

            if (singleQuotationMark == Boolean.FALSE) {

                if (nbtList.charAt(m.start()) == '{' || nbtList.charAt(m.start()) == '[') {
                    mutableText.append(Text.literal(String.valueOf(nbtList.charAt(m.start()))).formatted(separationColour));
                    lastString = String.valueOf(nbtList.charAt(m.start()));
                    lastIndex = m.start();
                }

                if (nbtList.charAt(m.start()) == '}' || nbtList.charAt(m.start()) == ']' || nbtList.charAt(m.start()) == ',') {
                    if (nbtList.charAt(m.start() - 1) == 's' || nbtList.charAt(m.start() - 1) == 'S' ||
                            nbtList.charAt(m.start() - 1) == 'b' || nbtList.charAt(m.start() - 1) == 'B' ||
                            nbtList.charAt(m.start() - 1) == 'l' || nbtList.charAt(m.start() - 1) == 'L' ||
                            nbtList.charAt(m.start() - 1) == 'f' || nbtList.charAt(m.start() - 1) == 'F'
                    ) {
                        mutableText.append(Text.literal(nbtList.substring(lastIndex + 1, m.start() - 1)).formatted(integerColour));
                        mutableText.append(Text.literal(nbtList.substring(m.start() - 1, m.start())).formatted(typeColour));

                    } else {
                        mutableText.append(Text.literal(nbtList.substring(lastIndex + 1, m.start())).formatted(integerColour));
                    }

                    mutableText.append(Text.literal(String.valueOf(nbtList.charAt(m.start())))).formatted(separationColour);

                    if (nbtList.charAt(m.start()) == ',') {
                        mutableText.append(Text.literal(" ").formatted(separationColour));
                    }
                    lastString = String.valueOf(nbtList.charAt(m.start()));
                    lastIndex = m.start();
                }

                if (nbtList.charAt(m.start()) == ':') { // 4).
                    if (!lastString.equals("\"")) {
                        mutableText.append(Text.literal(nbtList.substring(lastIndex + 1, m.start())).formatted(fieldColour));

                        mutableText.append((Text.literal(String.valueOf(nbtList.charAt(m.start())))).formatted(separationColour));
                        mutableText.append(Text.literal(" ").formatted(separationColour));
                        lastString = String.valueOf(nbtList.charAt(m.start()));
                        lastIndex = m.start();
                    }
                }

                if (nbtList.charAt(m.start()) == '"') {
                    if (lastString.equals("\"")) {

                        if (m.start() - lineLimit > lineStep) {
                            mutableText.append(Text.literal("....").formatted(lstringColour));
                            removedCharters += m.start() - lineLimit;
                        } else {
                            mutableText.append(Text.literal(nbtList.substring(lastIndex + 1, m.start())).formatted(stringColour));
                        }

                        mutableText.append(Text.literal(String.valueOf(nbtList.charAt(m.start()))).formatted(quotationColour));
                    } else {
                        mutableText.append(Text.literal(String.valueOf(nbtList.charAt(m.start()))).formatted(quotationColour));
                    }
                    lastString = String.valueOf(nbtList.charAt(m.start()));
                    lastIndex = m.start();

                }
            }

            if (m.start() - removedCharters >= lineLimit) { // 1).
                if (nbtList.charAt(m.start()) == '}' || nbtList.charAt(m.start()) == ']' || nbtList.charAt(m.start()) == ',') { // 2).

                    if (lastString.equals("'")) { // 3).
                        mutableText.append(Text.literal(nbtList.substring(lastIndex + 1, m.start())).formatted(stringColour));
                        lastIndex = m.start();
                    }

                    list.add(indexInsertLocation, mutableText);
                    indexInsertLocation += 1;
                    mutableText = Text.literal("     ");
                    lineAdded = Boolean.TRUE;
                    lineLimit = lineLimit + lineStep;
                }
            }
        }

        if (lineAdded.equals(Boolean.FALSE)) {
            list.add(indexInsertLocation, mutableText);
        }

        return list;
    }
}