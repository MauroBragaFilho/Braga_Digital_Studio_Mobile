#include "bdsm_dock.hpp"

#include <QComboBox>
#include <QDesktopServices>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QScrollArea>
#include <QUrl>
#include <QVBoxLayout>
#include <algorithm>

#include <obs-module.h>

#include "device_manager.hpp"
#include "mdns_discovery.hpp"
#include "obs_ndi_integration.hpp"
#include "obs_scene_graph.hpp"

namespace bdsm_qt {

QString tr_(const char *key) { return QString::fromUtf8(obs_module_text(key)); }

namespace {

const char *kRed = "#e53935";
const char *kGreen = "#43a047";
const char *kGrey = "#6b6b6b";
const char *kHowToInstallUrl = "https://github.com/DistroAV/DistroAV/wiki/1.-Installation";

// Texto + cor do tally (a cor NUNCA e o unico indicador: sempre ha o texto).
void tallyLook(const std::string &state, const char *&color, QString &label)
{
	color = kGrey;
	label = tr_("BdsmLink.TallyOff");
	if (state == "PROGRAM") {
		color = kRed;
		label = tr_("BdsmLink.TallyProgram");
	} else if (state == "PREVIEW") {
		color = kGreen;
		label = tr_("BdsmLink.TallyPreview");
	}
}

QString statusText(const Device &d)
{
	switch (d.status) {
	case ConnStatus::Connected:
		return tr_("BdsmLink.Status.Connected");
	case ConnStatus::Connecting:
		return tr_("BdsmLink.Status.Connecting");
	case ConnStatus::Pending:
		if (!d.pairCode.isEmpty())
			return tr_("BdsmLink.Status.PendingCode").arg(d.pairCode);
		return tr_("BdsmLink.Status.Pending") + (d.detail.isEmpty() ? QString() : QStringLiteral(" (%1)").arg(d.detail));
	case ConnStatus::Unpaired:
		return d.detail.isEmpty() ? tr_("BdsmLink.Status.Unpaired") : tr_("BdsmLink.Status.UnpairedShort") + ": " + d.detail;
	case ConnStatus::Error:
		return tr_("BdsmLink.Status.Error") + (d.detail.isEmpty() ? QString() : ": " + d.detail);
	case ConnStatus::Disconnected:
	default:
		return tr_("BdsmLink.Status.Disconnected") + (d.detail.isEmpty() ? QString() : ": " + d.detail);
	}
}

} // namespace

// ------------------------------------------------------------------ DeviceCard
DeviceCard::DeviceCard(const std::string &key, QWidget *parent) : QFrame(parent), key_(key)
{
	setObjectName(QStringLiteral("bdsmCard"));
	auto *root = new QVBoxLayout(this);
	root->setContentsMargins(8, 6, 8, 6);
	root->setSpacing(3);

	auto *head = new QHBoxLayout;
	name_ = new QLabel(this);
	name_->setStyleSheet(QStringLiteral("font-weight: bold;"));
	tally_ = new QLabel(this);
	tally_->setAlignment(Qt::AlignCenter);
	tally_->setMinimumWidth(78);
	head->addWidget(name_, 1);
	head->addWidget(tally_, 0);
	root->addLayout(head);

	status_ = new QLabel(this);
	status_->setWordWrap(true);
	telemetry_ = new QLabel(this);
	telemetry_->setWordWrap(true);
	ndi_ = new QLabel(this);
	ndi_->setWordWrap(true);
	distro_ = new QLabel(this);
	ndiState_ = new QLabel(this);
	ndiState_->setWordWrap(true);
	root->addWidget(status_);
	root->addWidget(telemetry_);
	root->addWidget(ndi_);
	root->addWidget(distro_);
	root->addWidget(ndiState_);

	auto *srcRow = new QHBoxLayout;
	srcRow->addWidget(new QLabel(tr_("BdsmLink.NdiSource") + ":", this), 0);
	source_ = new QComboBox(this);
	source_->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon);
	source_->setMinimumContentsLength(12);
	srcRow->addWidget(source_, 1);
	root->addLayout(srcRow);

	auto *ndiBtns = new QHBoxLayout;
	addNdi_ = new QPushButton(tr_("BdsmLink.AddNdiSource"), this);
	howTo_ = new QPushButton(tr_("BdsmLink.HowToInstallDistroAV"), this);
	ndiBtns->addWidget(addNdi_, 1);
	ndiBtns->addWidget(howTo_, 0);
	root->addLayout(ndiBtns);

	auto *btns = new QHBoxLayout;
	pair_ = new QPushButton(tr_("BdsmLink.Pair"), this);
	forget_ = new QPushButton(tr_("BdsmLink.Forget"), this);
	remove_ = new QPushButton(tr_("BdsmLink.Remove"), this);
	btns->addWidget(pair_);
	btns->addWidget(forget_);
	btns->addStretch(1);
	btns->addWidget(remove_);
	root->addLayout(btns);

	connect(addNdi_, &QPushButton::clicked, this, [this] { emit addNdiClicked(key_); });
	connect(howTo_, &QPushButton::clicked, this, [this] { emit howToClicked(); });
	connect(pair_, &QPushButton::clicked, this, [this] { emit pairClicked(key_); });
	connect(forget_, &QPushButton::clicked, this, [this] { emit forgetClicked(key_); });
	connect(remove_, &QPushButton::clicked, this, [this] { emit removeClicked(key_); });
	connect(source_, QOverload<int>::of(&QComboBox::activated), this,
		[this](int idx) { emit mappingChanged(key_, source_->itemData(idx).toString().toStdString()); });
}

void DeviceCard::update(const Device &d, const QStringList &ndiNames, const NdiCardInfo &ndiInfo)
{
	name_->setText(QStringLiteral("%1  [%2]").arg(d.displayName(), QString::fromStdString(d.key)));

	// Tally: borda e selo coloridos (PROGRAM vermelho, PREVIEW verde)
	const char *color = kGrey;
	QString label;
	tallyLook(d.tally, color, label);
	setStyleSheet(QStringLiteral("QFrame#bdsmCard { border: 3px solid %1; border-radius: 6px; }").arg(QLatin1String(color)));
	tally_->setText(label);
	tally_->setStyleSheet(QStringLiteral("background:%1; color:white; font-weight:bold; border-radius:3px; padding:2px 6px;")
				      .arg(QLatin1String(color)));

	status_->setText(statusText(d));

	if (d.state && d.status == ConnStatus::Connected) {
		const auto &s = *d.state;
		QString bat = QStringLiteral("%1%").arg(s.battery_level);
		if (s.is_charging)
			bat += QStringLiteral(" (%1)").arg(tr_("BdsmLink.Charging"));
		bool low = s.battery_level < 20; // alerta visual de bateria baixa (< 20%)
		auto red = [](const QString &t) {
			return QStringLiteral("<b><span style=\"color:") + QLatin1String(kRed) + QStringLiteral("\">") +
			       t.toHtmlEscaped() + QStringLiteral("</span></b>");
		};
		auto kv = [](const QString &label, const QString &htmlValue) {
			return label.toHtmlEscaped() + QStringLiteral(": ") + htmlValue;
		};
		QStringList parts;
		parts << kv(tr_("BdsmLink.Battery"), low ? red(bat + " - " + tr_("BdsmLink.BatteryLow")) : bat.toHtmlEscaped());
		parts << kv(tr_("BdsmLink.Lens"), QString::fromStdString(s.camera_lens).toHtmlEscaped());
		parts << kv(tr_("BdsmLink.Source"), QString::fromStdString(s.capture_source).toHtmlEscaped());
		parts << kv(tr_("BdsmLink.Fps"), QString::number(s.fps));
		parts << kv(tr_("BdsmLink.Rec"), s.is_recording ? red(tr_("BdsmLink.Yes")) : tr_("BdsmLink.No").toHtmlEscaped());
		telemetry_->setTextFormat(Qt::RichText);
		telemetry_->setText(parts.join(QStringLiteral(" | ")));
		auto ndi = s.ndi_source_name();
		ndi_->setText(QStringLiteral("%1: %2 | %3: %4")
				      .arg(tr_("BdsmLink.Ndi"), ndi ? QString::fromStdString(*ndi) : tr_("BdsmLink.NdiOff"),
					   tr_("BdsmLink.Mic"), QString::fromStdString(s.microphone)));
		telemetry_->show();
		ndi_->show();
	} else {
		telemetry_->hide();
		ndi_->hide();
	}

	// Integracao com o DistroAV: indicador, estado da fonte NDI deste celular e botao
	const bdsm::AddPlan &plan = ndiInfo.plan;
	distro_->setText(QStringLiteral("DistroAV: %1")
				 .arg(ndiInfo.distroav ? tr_("BdsmLink.DistroInstalled") : tr_("BdsmLink.DistroMissing")));
	distro_->setStyleSheet(QStringLiteral("color:%1; font-weight:bold;")
				       .arg(QLatin1String(ndiInfo.distroav ? kGreen : kRed)));
	QString info;
	bool canAdd = false;
	if (!ndiInfo.distroav) {
		info = tr_("BdsmLink.DistroMissingHelp");
	} else {
		const QString src = QString::fromStdString(plan.source_name);
		switch (plan.action) {
		case bdsm::AddAction::NoNdiName:
			info = tr_("BdsmLink.NdiOffHelp");
			break;
		case bdsm::AddAction::AlreadyInScene:
			info = tr_("BdsmLink.SourceInScene").arg(src, ndiInfo.targetScene);
			break;
		case bdsm::AddAction::AddExisting: {
			QStringList scenes;
			for (const auto &sc : plan.in_scenes)
				scenes << QString::fromStdString(sc);
			info = scenes.isEmpty() ? tr_("BdsmLink.SourceExistsNoScene").arg(src)
						: tr_("BdsmLink.SourceExistsIn").arg(src, scenes.join(QStringLiteral(", ")));
			canAdd = true;
			break;
		}
		case bdsm::AddAction::CreateNew:
			info = tr_("BdsmLink.SourceWillBeCreated").arg(src, QString::fromStdString(plan.ndi_name));
			canAdd = true;
			break;
		}
	}
	ndiState_->setText(info);
	addNdi_->setEnabled(canAdd);
	addNdi_->setToolTip(canAdd ? tr_("BdsmLink.AddNdiSourceTip").arg(ndiInfo.targetScene) : info);
	howTo_->setVisible(!ndiInfo.distroav);

	// Seletor de fonte NDI: so reconstroi se a lista mudou (nao atrapalha o usuario escolhendo)
	QString mapping = QString::fromStdString(d.mapping);
	QStringList sig = ndiNames;
	sig.append(QStringLiteral("|") + mapping);
	if (sig != lastSig_) {
		lastSig_ = sig;
		source_->blockSignals(true);
		source_->clear();
		source_->addItem(tr_("BdsmLink.NdiAuto"), QString());
		for (const QString &n : ndiNames)
			source_->addItem(n, n);
		if (!mapping.isEmpty() && !ndiNames.contains(mapping))
			source_->addItem(tr_("BdsmLink.NdiMissing").arg(mapping), mapping);
		int idx = source_->findData(mapping);
		source_->setCurrentIndex(idx < 0 ? 0 : idx);
		source_->blockSignals(false);
	}

	bool paired = d.status == ConnStatus::Connected || d.status == ConnStatus::Connecting ||
		      d.status == ConnStatus::Disconnected;
	pair_->setEnabled(d.status != ConnStatus::Pending);
	pair_->setText(paired ? tr_("BdsmLink.Repair") : tr_("BdsmLink.Pair"));
	forget_->setEnabled(d.status != ConnStatus::Unpaired || d.state.has_value());
}

// ------------------------------------------------------------------ BdsmDock
BdsmDock::BdsmDock(DeviceManager *mgr, QWidget *parent) : QWidget(parent), mgr_(mgr)
{
	disc_ = new MdnsDiscovery(this);
	auto *root = new QVBoxLayout(this);
	root->setContentsMargins(6, 6, 6, 6);

	auto *addRow = new QHBoxLayout;
	addEdit_ = new QLineEdit(this);
	addEdit_->setPlaceholderText(tr_("BdsmLink.AddPlaceholder"));
	addBtn_ = new QPushButton(tr_("BdsmLink.Add"), this);
	addRow->addWidget(addEdit_, 1);
	addRow->addWidget(addBtn_, 0);
	root->addLayout(addRow);

	discoverBtn_ = new QPushButton(tr_("BdsmLink.Discover"), this);
	root->addWidget(discoverBtn_);

	summary_ = new QLabel(this);
	root->addWidget(summary_);
	onAir_ = new QLabel(this);
	onAir_->setAlignment(Qt::AlignCenter);
	root->addWidget(onAir_);
	message_ = new QLabel(this);
	message_->setWordWrap(true);
	message_->hide();
	root->addWidget(message_);

	scroll_ = new QScrollArea(this);
	scroll_->setWidgetResizable(true);
	scroll_->setFrameShape(QFrame::NoFrame);
	listHost_ = new QWidget;
	listLayout_ = new QVBoxLayout(listHost_);
	listLayout_->setContentsMargins(0, 0, 0, 0);
	listLayout_->setSpacing(6);
	empty_ = new QLabel(tr_("BdsmLink.NoDevices"), listHost_);
	empty_->setWordWrap(true);
	listLayout_->addWidget(empty_);
	listLayout_->addStretch(1);
	scroll_->setWidget(listHost_);
	root->addWidget(scroll_, 1);

	refreshTimer_.setSingleShot(true);
	refreshTimer_.setInterval(120);
	periodic_.setInterval(3000);
	messageTimer_.setSingleShot(true);
	messageTimer_.setInterval(15000);

	connect(&refreshTimer_, &QTimer::timeout, this, &BdsmDock::refresh);
	connect(&periodic_, &QTimer::timeout, this, &BdsmDock::scheduleRefresh);
	connect(&messageTimer_, &QTimer::timeout, message_, &QLabel::hide);
	connect(addBtn_, &QPushButton::clicked, this, &BdsmDock::onAdd);
	connect(addEdit_, &QLineEdit::returnPressed, this, &BdsmDock::onAdd);
	connect(discoverBtn_, &QPushButton::clicked, this, &BdsmDock::onDiscover);
	connect(disc_, &MdnsDiscovery::finished, this, &BdsmDock::onDiscoveryFinished);
	if (mgr_) {
		connect(mgr_, &DeviceManager::changed, this, &BdsmDock::scheduleRefresh);
		connect(mgr_, &DeviceManager::notice, this, &BdsmDock::onNotice);
	}
	periodic_.start();
	refresh();
}

BdsmDock::~BdsmDock() = default;

void BdsmDock::scheduleRefresh()
{
	if (!refreshTimer_.isActive())
		refreshTimer_.start();
}

void BdsmDock::showMessage(const QString &text, bool error)
{
	message_->setStyleSheet(error ? QStringLiteral("color:%1; font-weight:bold;").arg(QLatin1String(kRed))
				      : QString());
	message_->setText(text);
	message_->show();
	messageTimer_.start();
}

void BdsmDock::onNotice(const QString &message, bool warning) { showMessage(message, warning); }

void BdsmDock::onAdd()
{
	if (!mgr_)
		return;
	QString err;
	if (mgr_->addDevice(addEdit_->text().trimmed(), &err)) {
		addEdit_->clear();
	} else {
		showMessage(tr_("BdsmLink.InvalidAddress") + ": " + addEdit_->text(), true);
	}
}

void BdsmDock::onDiscover()
{
	if (disc_->running())
		return;
	discoverBtn_->setEnabled(false);
	discoverBtn_->setText(tr_("BdsmLink.Discovering"));
	disc_->start();
}

void BdsmDock::onDiscoveryFinished(const std::vector<bdsm::MdnsEndpoint> &found)
{
	discoverBtn_->setEnabled(true);
	discoverBtn_->setText(tr_("BdsmLink.Discover"));
	if (!mgr_)
		return;
	int added = 0;
	for (const auto &e : found) {
		std::string key = e.ip + ":" + std::to_string(e.port);
		if (!mgr_->device(key)) {
			mgr_->addDevice(QString::fromStdString(key));
			++added;
		}
	}
	showMessage(tr_("BdsmLink.DiscoverResult").arg((int)found.size()).arg(added), false);
}

void BdsmDock::refresh()
{
	if (!mgr_) {
		summary_->setText(QString());
		return;
	}
	QStringList ndiNames;
	ObsNdiScan scan;
	try {
		scan = scan_ndi();
		for (const auto &s : scan.sources)
			ndiNames << QString::fromStdString(s.name);
		ndiNames.sort();
		ndiNames.removeDuplicates();
	} catch (...) {
	}

	auto devs = mgr_->devices();
	// remove cartoes de celulares que sumiram
	for (auto it = cards_.begin(); it != cards_.end();) {
		bool still = std::any_of(devs.begin(), devs.end(), [&](const Device *d) { return d->key == it->first; });
		if (!still) {
			it->second->deleteLater();
			it = cards_.erase(it);
		} else {
			++it;
		}
	}
	// cria os novos e atualiza todos, na ordem da lista
	int pos = 0;
	for (const Device *d : devs) {
		DeviceCard *card;
		auto it = cards_.find(d->key);
		if (it == cards_.end()) {
			card = new DeviceCard(d->key, listHost_);
			cards_[d->key] = card;
			connect(card, &DeviceCard::pairClicked, this, [this](const std::string &k) {
				if (mgr_)
					mgr_->pair(k);
			});
			connect(card, &DeviceCard::forgetClicked, this, [this](const std::string &k) {
				if (mgr_)
					mgr_->forget(k);
			});
			connect(card, &DeviceCard::removeClicked, this, [this](const std::string &k) {
				if (mgr_)
					mgr_->removeDevice(k);
			});
			connect(card, &DeviceCard::addNdiClicked, this, [this](const std::string &k) { onAddNdi(k); });
			connect(card, &DeviceCard::howToClicked, this,
				[] { QDesktopServices::openUrl(QUrl(QString::fromLatin1(kHowToInstallUrl))); });
			connect(card, &DeviceCard::mappingChanged, this, [this](const std::string &k, const std::string &s) {
				if (mgr_)
					mgr_->setMapping(k, s);
			});
		} else {
			card = it->second;
		}
		if (listLayout_->indexOf(card) != pos)
			listLayout_->insertWidget(pos, card);
		NdiCardInfo ni;
		ni.distroav = scan.distroav;
		ni.targetScene = QString::fromStdString(scan.target_scene);
		ni.plan = bdsm::plan_add_ndi_source(d->displayName().toStdString(), d->ndiStreamName(), scan.target_scene,
						    scan.sources, scan.taken_names);
		card->update(*d, ndiNames, ni);
		++pos;
	}
	empty_->setVisible(devs.empty());
	summary_->setText(tr_("BdsmLink.Summary").arg((int)devs.size()).arg(mgr_->connectedCount()));

	// Indicador global: quantos celulares estao NO AR (texto + cor).
	int onAir = mgr_->onAirCount();
	QString onAirText = onAir == 0   ? tr_("BdsmLink.OnAirNone")
			    : onAir == 1 ? tr_("BdsmLink.OnAirOne")
					 : tr_("BdsmLink.OnAirMany").arg(onAir);
	onAir_->setText(onAirText);
	onAir_->setStyleSheet(QStringLiteral("background:%1; color:white; font-weight:bold; border-radius:3px; padding:4px;")
				      .arg(QLatin1String(onAir > 0 ? kRed : kGrey)));
}

void BdsmDock::onAddNdi(const std::string &key)
{
	if (!mgr_)
		return;
	const Device *d = mgr_->device(key);
	if (!d)
		return;
	AddOutcome o;
	try {
		o = add_ndi_source_for(d->displayName().toStdString(), d->ndiStreamName());
	} catch (const std::exception &e) { // nunca derrubar o OBS
		showMessage(QString::fromUtf8(e.what()), true);
		return;
	}
	if (!o.ok) {
		showMessage(tr_(o.error_key.c_str()), true);
		return;
	}
	const QString src = QString::fromStdString(o.source_name);
	const QString scene = QString::fromStdString(o.target_scene);
	// Registra o mapeamento dispositivo -> fonte do OBS (usado pelo tally).
	mgr_->setMapping(key, o.source_name);
	switch (o.action) {
	case bdsm::AddAction::CreateNew:
		showMessage(tr_("BdsmLink.Added").arg(src, scene), false);
		break;
	case bdsm::AddAction::AddExisting:
		showMessage(tr_("BdsmLink.AddedExisting").arg(src, scene), false);
		break;
	default:
		showMessage(tr_("BdsmLink.AlreadyThere").arg(src, scene), false);
		break;
	}
	scheduleRefresh();
}

} // namespace bdsm_qt
