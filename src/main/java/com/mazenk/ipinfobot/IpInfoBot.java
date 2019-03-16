package com.mazenk.ipinfobot;

import com.jtelegram.api.TelegramBot;
import com.jtelegram.api.TelegramBotRegistry;
import com.jtelegram.api.events.inline.InlineQueryEvent;
import com.jtelegram.api.inline.InlineQuery;
import com.jtelegram.api.inline.input.InputMessageContent;
import com.jtelegram.api.inline.input.InputTextMessageContent;
import com.jtelegram.api.inline.result.InlineResultArticle;
import com.jtelegram.api.inline.result.InlineResultLocation;
import com.jtelegram.api.inline.result.framework.InlineResult;
import com.jtelegram.api.requests.inline.AnswerInlineQuery;
import com.jtelegram.api.update.PollingUpdateProvider;
import com.jtelegram.api.util.TextBuilder;
import io.ipinfo.api.IPInfo;
import io.ipinfo.api.cache.SimpleCache;
import io.ipinfo.api.model.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IpInfoBot {
    private final ExecutorService responderService = Executors.newFixedThreadPool(4);
    private TelegramBot bot;
    private IPInfo apiClient;

    public static void main(String[] args) {
        new IpInfoBot();
    }

    IpInfoBot() {
        apiClient = IPInfo.builder()
                .setToken(System.getenv("IPINFO_TOKEN"))
                .setCache(new SimpleCache(Duration.ofDays(1)))
                .build();

        TelegramBotRegistry.builder()
                .updateProvider(new PollingUpdateProvider())
                .build()
                .registerBot(System.getenv("BOT_TOKEN"), (bot, err) -> {
                    if (err != null) {
                        System.out.println("Unable to login to Telegram Bot! Shutting down...");
                        err.printStackTrace();
                        System.exit(-127);
                        return;
                    }

                    this.bot = bot;
                    bot.getEventRegistry().registerEvent(InlineQueryEvent.class, this::handleInlineQuery);

                    System.out.println("Successfully logged in as " + bot.getBotInfo().getUsername());
                });
    }

    private void handleInlineQuery(InlineQueryEvent event) {
        InlineQuery query = event.getQuery();

        responderService.submit(() -> {
            List<InlineResult> results = new ArrayList<>();

            if (!query.getQuery().trim().isEmpty()) {
                boolean ip = false;

                try {
                    IPResponse ipResponse = apiClient.lookupIP(query.getQuery());
                    ip = true;

                    // only provide responses for non-empty queries
                    // (empty query will expose the IP of the bot)
                    if (ipResponse != null) {
                        // always provide a general summary
                        results.add(createSummary(ipResponse));

                        if (ipResponse.getLocation() != null) {
                            // add location image
                            results.add(createLocation(ipResponse));
                            // add location as text
                            results.add(createLocationSummary(ipResponse));
                        }

                        if (ipResponse.getAsn() != null) {
                            results.add(createIPASNSummary(ipResponse));
                        }

                        if (ipResponse.getCompany() != null) {
                            // include summary of just the company info
                            results.add(createCompanySummary(ipResponse));
                        }
                    }
                } catch (Exception ex) {
                    // provide a generic message on error (for now)
                    results.add(createGenericMessage());

                    if (ip) {
                        ex.printStackTrace();
                    }
                }

                if (!ip) {
                    try {
                        ASNResponse asnResponse = apiClient.lookupASN(query.getQuery());

                        if (asnResponse != null) {
                            results.add(createASNSummary(asnResponse));
                            results.add(createASNPrefixList(asnResponse));
                        } else {
                            results.add(createGenericMessage());
                        }
                    } catch (Exception ex) {
                        results.add(createGenericMessage());
                        ex.printStackTrace();
                    }
                }
            } else {
                results.add(createGenericMessage());
            }

            bot.perform(AnswerInlineQuery.builder()
                    .queryId(query.getId())
                    .results(results)
                    .cacheTime(1500)
                    .isPersonal(false)
                    .build()
            );
        });
    }

    private InlineResultArticle createGenericMessage() {
        return InlineResultArticle.builder()
                .id("-1")
                .title("IPInfo Bot")
                .description("Look up any IP/ASN. Try it now!")
                .inputMessageContent(builderToInput(TextBuilder.create().plain("You weren't meant to click me...")))
                .build();
    }

    private InlineResultArticle createSummary(IPResponse ip) {
        TextBuilder builder = TextBuilder.create();

        builder.bold("-- Summary of ").italics(ip.getIp()).bold(" --")
                .newLine().newLine();

        if (ip.getHostname() != null) {
            builder.plain("Hostname: ").code(ip.getHostname()).newLine().newLine();
        }

        if (ip.getLocation() != null) {
            addLocationText(builder, ip);
        }

        if (ip.getCompany() != null) {
            addCompanyText(builder, ip);
        }

        if (ip.getAsn() != null) {
            addIPASNText(builder, ip.getAsn());
        }

        if (ip.getOrg() != null) {
            builder.plain("Organization: ").italics(ip.getOrg());
        }

        if (ip.getPhone() != null) {
            builder.plain("Phone Number: ").code(ip.getPhone());
        }

        return InlineResultArticle.builder()
                .id("1")
                .title("Full Summary")
                .description("Send a full summary of " + ip.getIp())
                .inputMessageContent(builderToInput(builder))
                .build();
    }

    private InlineResultArticle createLocationSummary(IPResponse ip) {
        TextBuilder builder = TextBuilder.create();

        builder.bold("-- Location of ").italics(ip.getIp()).bold(" --")
                .newLine().newLine();

        addLocationText(builder, ip);

        return InlineResultArticle.builder()
                .id("5")
                .title("Location Summary")
                .description("Send the location data of " + ip.getIp() + " as text")
                .inputMessageContent(builderToInput(builder))
                .build();
    }

    private InlineResultArticle createCompanySummary(IPResponse ip) {
        TextBuilder builder = TextBuilder.create();

        builder.bold("-- Company Information of ").italics(ip.getIp()).bold(" --")
                .newLine();

        addLocationText(builder, ip);

        return InlineResultArticle.builder()
                .id("3")
                .title("Company Summary")
                .description("Send the company data of " + ip.getIp())
                .inputMessageContent(builderToInput(builder))
                .build();
    }

    private InlineResultArticle createIPASNSummary(IPResponse ip) {
        TextBuilder builder = TextBuilder.create();

        builder.bold("-- ASN Information for ").italics(ip.getIp()).bold(" --").newLine();
        addIPASNText(builder, ip.getAsn());

        return InlineResultArticle.builder()
                .id("4")
                .title("IP ASN Summary")
                .description("Send the ASN data for " + ip.getIp())
                .inputMessageContent(builderToInput(builder))
                .build();
    }

    private InlineResultArticle createASNSummary(ASNResponse response) {
        TextBuilder builder = TextBuilder.create();

        builder.bold("-- Summary of ").italics(response.getAsn()).bold(" --")
                .newLine().newLine();

        builder.plain("Label: ").code(response.getAsn()).newLine();
        builder.plain("Name: ").italics(response.getName()).newLine();

        if (response.getDomain() != null) {
            builder.plain("Domain: ").code(response.getDomain()).newLine();
        }

        if (response.getCountry() != null) {
            builder.plain("Country: ").code(response.getCountry()).newLine();
        }

        if (response.getRegistry() != null) {
            builder.plain("Registry: ").italics(response.getRegistry()).newLine();
        }

        if (response.getAllocated() != null) {
            builder.plain("Allocation Date: ").italics(response.getAllocated()).newLine();
        }

        builder.plain("Number of IPs: ").code(response.getNumIps()).newLine();
        builder.plain("IPv4 prefixes: ").code(String.valueOf(response.getPrefixes().size())).newLine();
        builder.plain("IPv6 prefixes: ").code(String.valueOf(response.getPrefixes6().size())).newLine();

        return InlineResultArticle.builder()
                .id("1")
                .title("General Summary")
                .description("Send a general summary of " + response.getAsn())
                .inputMessageContent(builderToInput(builder))
                .build();
    }

    private InlineResultArticle createASNPrefixList(ASNResponse response) {
        TextBuilder builder = TextBuilder.create();

        builder.bold("-- ASN Prefix List of ").italics(response.getAsn()).bold(" --")
                .newLine().newLine();

        List<Prefix> prefixes = new ArrayList<>(response.getPrefixes());
        prefixes.addAll(response.getPrefixes6());

        prefixes.forEach((prefix) -> {
            builder.code(prefix.getNetblock());

            if (prefix.getId() != null) {
                builder.plain(" -- ")
                        .code(prefix.getId()).plain(", ")
                        .italics(prefix.getNetblock()).plain(", ")
                        .italics(prefix.getCountry());
            }

            builder.newLine();
        });

        return InlineResultArticle.builder()
                .id("6")
                .title("ASN Prefix List")
                .description("See the list of prefixes " + response.getAsn())
                .inputMessageContent(builderToInput(builder))
                .build();
    }

    private InlineResultLocation createLocation(IPResponse ip) {
        return InlineResultLocation.builder()
                .id("2")
                .title("Location")
                .longitude(Float.parseFloat(ip.getLongitude()))
                .latitude(Float.parseFloat(ip.getLatitude()))
                .build();
    }

    private void addCompanyText(TextBuilder builder, IPResponse ip) {
        Company company = ip.getCompany();

        builder.plain("Company: ").italics(company.getName()).italics(", ").italics(company.getDomain()).newLine();

        if (company.getType() != null) {
            builder.plain("Company Type: ").italics(company.getType().toUpperCase()).newLine();
        }
    }

    private void addLocationText(TextBuilder builder, IPResponse ip) {
        builder.bold("Location Info: ").newLine();
        builder.plain("- Longitude: ").code(ip.getLongitude()).newLine();
        builder.plain("- Latitude: ").code(ip.getLatitude()).newLine();
        builder.plain("- General Area: ");

        if (ip.getCity() != null) {
            builder.italics(ip.getCity()).italics(", ");
        }

        if (ip.getRegion() != null) {
            builder.italics(ip.getRegion()).space();
        }

        if (ip.getCountryName() != null) {
            builder.italics(ip.getCountryName());
        }

        builder.newLine();

        if (ip.getPostal() != null) {
            builder.plain("- Postal Code: ").italics(ip.getPostal()).newLine();
        }

        builder.newLine();
    }

    private void addIPASNText(TextBuilder builder, ASN asn) {
        builder.bold("ASN Info:").newLine();
        builder.plain("- Label: ").code(asn.getAsn()).newLine();
        builder.plain("- Name: ").italics(asn.getName());

        if (asn.getDomain() != null) {
            builder.plain(", ").code(asn.getDomain());
        }

        builder.newLine();

        if (asn.getRoute() != null) {
            builder.plain("- Route: ").code(asn.getRoute()).newLine();
        }

        builder.plain("- Type: ").italics(asn.getType().toUpperCase()).newLine();
    }

    private InputMessageContent builderToInput(TextBuilder builder) {
        return InputTextMessageContent.builder().messageText(builder).build();
    }
}
