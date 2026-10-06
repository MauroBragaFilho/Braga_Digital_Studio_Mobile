// Dock do OBS (View > Docks > "BDSM Link"): lista os celulares BDSM com estado
// de conexao, bateria, lente, fonte, FPS, REC, tally e o seletor da fonte NDI.
#pragma once

#include <QFrame>
#include <QPointer>
#include <QString>
#include <QTimer>
#include <QWidget>
#include <map>
#include <string>
#include <vector>

#include "mdns.hpp"
#include "ndi_link.hpp"

class QComboBox;
class QLabel;
class QLineEdit;
class QPushButton;
class QScrollArea;
class QVBoxLayout;

namespace bdsm_qt {

class DeviceManager;
class MdnsDiscovery;
struct Device;

// Texto localizado (locale/*.ini) -> QString. Chaves "BdsmLink.*".
QString tr_(const char *key);

// O que o cartao mostra sobre a integracao com o DistroAV (calculado pelo dock a cada atualizacao).
struct NdiCardInfo {
	bool distroav = false; // `ndi_source` registrado no OBS
	QString targetScene;   // cena que receberia a fonte
	bdsm::AddPlan plan;    // decisao pura: criar / reutilizar / ja esta na cena / NDI desligado
};

// Cartao de UM celular.
class DeviceCard : public QFrame {
	Q_OBJECT
public:
	explicit DeviceCard(const std::string &key, QWidget *parent = nullptr);
	void update(const Device &d, const QStringList &ndiNames, const NdiCardInfo &ndi);

signals:
	void pairClicked(const std::string &key);
	void forgetClicked(const std::string &key);
	void removeClicked(const std::string &key);
	void mappingChanged(const std::string &key, const std::string &source);
	void addNdiClicked(const std::string &key);
	void howToClicked();

private:
	std::string key_;
	QLabel *name_, *tally_, *status_, *telemetry_, *ndi_, *distro_, *ndiState_;
	QComboBox *source_;
	QPushButton *pair_, *forget_, *remove_, *addNdi_, *howTo_;
	QStringList lastSig_;
};

class BdsmDock : public QWidget {
	Q_OBJECT
public:
	explicit BdsmDock(DeviceManager *mgr, QWidget *parent = nullptr);
	~BdsmDock() override;

public slots:
	void scheduleRefresh();

private slots:
	void refresh();
	void onAdd();
	void onDiscover();
	void onDiscoveryFinished(const std::vector<bdsm::MdnsEndpoint> &found);
	void onNotice(const QString &message, bool warning);

private:
	void showMessage(const QString &text, bool error);
	void onAddNdi(const std::string &key);

	QPointer<DeviceManager> mgr_;
	MdnsDiscovery *disc_;
	QLineEdit *addEdit_;
	QPushButton *addBtn_, *discoverBtn_;
	QLabel *summary_, *onAir_, *message_, *empty_;
	QScrollArea *scroll_;
	QWidget *listHost_;
	QVBoxLayout *listLayout_;
	std::map<std::string, DeviceCard *> cards_;
	QTimer refreshTimer_;  // coalesce atualizacoes
	QTimer periodic_;      // relista as fontes NDI do OBS (podem surgir depois)
	QTimer messageTimer_;
};

} // namespace bdsm_qt
